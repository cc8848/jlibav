/*
 * Copyright (C) 2012 Ondrej Perutka
 *
 * This program is free software: you can redistribute it and/or 
 * modify it under the terms of the GNU Lesser General Public 
 * License as published by the Free Software Foundation, either 
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public 
 * License along with this library. If not, see 
 * <http://www.gnu.org/licenses/>.
 */
package org.libav.audio;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.bridj.Pointer;
import org.libav.CopyTimestampGenerator;
import org.libav.IEncoder;
import org.libav.ITimestampGenerator;
import org.libav.LibavException;
import org.libav.avcodec.*;
import org.libav.avcodec.bridge.AVCodecLibrary;
import org.libav.avformat.IStreamWrapper;
import org.libav.avutil.bridge.AVMediaType;
import org.libav.avutil.bridge.AVSampleFormat;
import org.libav.avutil.bridge.AVUtilLibrary;
import org.libav.bridge.LibraryManager;
import org.libav.data.IPacketConsumer;
import org.libav.util.Rational;

/**
 * Audio frame encoder.
 * 
 * @author Ondrej Perutka
 */
public class AudioFrameEncoder implements IEncoder {
    
    private static final AVUtilLibrary utilLib = LibraryManager.getInstance().getAVUtilLibrary();
    
    public static final int DEFAULT_OUTPUT_BUFFER_SIZE = 200000;
    
    private IStreamWrapper stream;
    private ICodecContextWrapper cc;
    private boolean smallLastFrame;
    
    private IFrameWrapper tmpFrame;
    private Pointer<Byte> frameData;
    private int frameSize;
    private int frameSampleCount;
    private long frameDuration;
    private Rational byteDuration;
    
    private Pointer<Byte> outputBuffer;
    private int outputBufferSize;
    private IPacketWrapper packet;
    
    private long flushFramePts;
    private int offset;
    private Rational ptsTransformBase;
    private ITimestampGenerator timestampGenerator;
    
    private final Set<IPacketConsumer> consumers;

    /**
     * Create a new audio frame wncoder for the given audio stream.
     * 
     * @param stream an audio stream
     * @throws LibavException if the encoder cannot be created for some reason
     * (caused by the Libav)
     */
    public AudioFrameEncoder(IStreamWrapper stream) throws LibavException {
        this.stream = stream;
        
        cc = stream.getCodecContext();
        cc.clearWrapperCache();
        if (cc.getCodecType() != AVMediaType.AVMEDIA_TYPE_AUDIO)
            throw new IllegalArgumentException("not an audio stream");
        
        smallLastFrame = false;
        
        tmpFrame = FrameWrapperFactory.getInstance().allocFrame();
        frameData = malloc(AVCodecLibrary.AVCODEC_MAX_AUDIO_FRAME_SIZE + AVCodecLibrary.FF_INPUT_BUFFER_PADDING_SIZE);
        frameSize = 0;
        frameSampleCount = 0;
        frameDuration = 0;
        byteDuration = null;
        
        outputBufferSize = DEFAULT_OUTPUT_BUFFER_SIZE;
        outputBuffer = malloc(outputBufferSize);
        packet = PacketWrapperFactory.getInstance().alloc();
        
        flushFramePts = 0;
        stream.clearWrapperCache();
        ptsTransformBase = stream.getTimeBase().mul(1000).invert();
        timestampGenerator = new CopyTimestampGenerator();
        
        consumers = Collections.synchronizedSet(new HashSet<IPacketConsumer>());
    }
    
    @Override
    public ICodecContextWrapper getCodecContext() {
        return cc;
    }
    
    @Override
    public IStreamWrapper getStream() {
        return stream;
    }

    @Override
    public ITimestampGenerator getTimestampGenerator() {
        return timestampGenerator;
    }

    @Override
    public void setTimestampGenerator(ITimestampGenerator timestampGenerator) {
        this.timestampGenerator = timestampGenerator;
    }
    
    @Override
    public synchronized void close() {
        cc.close();
        if (outputBuffer != null)
            utilLib.av_free(outputBuffer);
        if (packet != null)
            packet.free();
        if (tmpFrame != null)
            utilLib.av_free(tmpFrame.getData().get(0));
        
        outputBuffer = null;
        packet = null;
        tmpFrame = null;
    }
    
    @Override
    public boolean isClosed() {
        return outputBuffer == null;
    }
    
    /**
     * Get size of the output buffer in bytes (it is the buffer passed to the 
     * encoding function).
     * 
     * @return size of the output buffer
     */
    public int getOutputBufferSize() {
        return outputBufferSize;
    }

    /**
     * Set size of the output buffer (it is the buffer passed to the encoding 
     * function). DO NOT USE this method until you know what you are doing.
     * 
     * @param outputBufferSize a size in bytes
     */
    public synchronized void setOutputBufferSize(int outputBufferSize) {
        if (isClosed())
            return;
            
        utilLib.av_free(outputBuffer);
        outputBuffer = malloc(outputBufferSize);
        
        this.outputBufferSize = outputBufferSize;
    }
    
    private Pointer<Byte> malloc(int size) {
        Pointer<Byte> ptr = utilLib.av_malloc(size).as(Byte.class);
        if (ptr == null)
            throw new OutOfMemoryError("not enough memory for the audio frame encoder");
        
        return ptr;
    }
    
    @Override
    public synchronized void processFrame(Object producer, IFrameWrapper frame) throws LibavException {
        if (isClosed())
            return;
        if (cc.isClosed())
            openCodecContext();
        
        long pts;
        while ((pts = timestampGenerator.nextFrame(frame.getPts())) >= 0)
            encodeFrame(frame, pts);
    }
    
    @Override
    public synchronized void flush() throws LibavException {
        if (isClosed())
            return;
        if (cc.isClosed())
            openCodecContext();
        
        boolean flush = true;
        while (flush)
            flush = flushFrame();
    }
    
    private void openCodecContext() throws LibavException {
        cc.clearWrapperCache();
        ICodecWrapper codec = CodecWrapperFactory.getInstance().findEncoder(cc.getCodecId());
        cc.open(codec);
        cc.clearWrapperCache();
        
        smallLastFrame = (codec.getCapabilities() & AVCodecLibrary.CODEC_CAP_SMALL_LAST_FRAME) == AVCodecLibrary.CODEC_CAP_SMALL_LAST_FRAME;
        frameSampleCount = cc.getFrameSize();
        if ((codec.getCapabilities() & AVCodecLibrary.CODEC_CAP_VARIABLE_FRAME_SIZE) == AVCodecLibrary.CODEC_CAP_VARIABLE_FRAME_SIZE)
            frameSampleCount = 8192;
        if (frameSampleCount <= 1) // keep compatibility with older PCM encoders
            frameSampleCount = 8192;
        frameSize = frameSampleCount * cc.getChannels() * AVSampleFormat.getBytesPerSample(cc.getSampleFormat());
        frameDuration = 1000 * frameSampleCount / cc.getSampleRate();
        byteDuration = new Rational(frameDuration, frameSize);
        offset = 0;
    }
    
    private boolean flushFrame() throws LibavException {
        packet.init();
        packet.setData(outputBuffer);
        packet.setSize(outputBufferSize);
        
        int sampleCount = offset / (cc.getChannels() * AVSampleFormat.getBytesPerSample(cc.getSampleFormat()));
        if (sampleCount > 0) {
            if (sampleCount < frameSampleCount && !smallLastFrame) {
                sampleCount = frameSampleCount;
                frameData.clearBytesAtOffset(offset, frameSize - offset, (byte)0);
                offset = frameSize;
            }
            tmpFrame.fillAudioFrame(sampleCount, cc.getChannels(), cc.getSampleFormat(), frameData, offset);
        }
        offset = 0;

        boolean result;
        if (result = cc.encodeAudioFrame(sampleCount == 0 ? null : tmpFrame, packet)) {
            packet.clearWrapperCache();
            packet.setStreamIndex(stream.getIndex());
            packet.setPts(ptsTransformBase.mul(flushFramePts).longValue());
            packet.setDts(packet.getPts());
            sendPacket(packet);
            flushFramePts += frameDuration;
        }
        
        return result;
    }
    
    private void encodeFrame(IFrameWrapper frame, long pts) throws LibavException {
        Pointer<Byte> data = frame.getData().get(0);
        int tmp, size = frame.getLineSize().get(0);
        pts -= byteDuration.mul(offset).longValue();
        
        while (size > 0) {
            tmp = frameSize - offset;
            if (size < tmp)
                tmp = size;
            data.copyTo(frameData.offset(offset), tmp);
            offset += tmp;
            size -= tmp;
            data = data.offset(tmp);
            
            if (offset == frameSize) {
                offset = 0;
                tmpFrame.fillAudioFrame(frameSampleCount, cc.getChannels(), cc.getSampleFormat(), frameData, frameSize);
                
                packet.init();
                packet.setData(outputBuffer);
                packet.setSize(outputBufferSize);
                
                if (cc.encodeAudioFrame(tmpFrame, packet)) {
                    packet.clearWrapperCache();
                    //System.out.printf("encoding audio frame: pts = %d (pts_offset = %d, source_pts = %d)\n", pts, timestampGenerator.getOffset(), frame.getPts());
                    packet.setStreamIndex(stream.getIndex());
                    packet.setPts(ptsTransformBase.mul(pts).longValue());
                    packet.setDts(packet.getPts());
                    sendPacket(packet);
                    pts += frameDuration;
                    flushFramePts = pts;
                }
            }
        }
    }
    
    private void sendPacket(IPacketWrapper packet) throws LibavException {
        synchronized (consumers) {
            for (IPacketConsumer c : consumers)
                c.processPacket(this, packet);
        }
    }

    @Override
    public void addPacketConsumer(IPacketConsumer c) {
        consumers.add(c);
    }

    @Override
    public void removePacketConsumer(IPacketConsumer c) {
        consumers.remove(c);
    }
    
}