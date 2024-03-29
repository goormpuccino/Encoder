package com.goormpuccino.encoder;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

public class ScreenEncoder {

    private static final int DEFAULT_FRAME_RATE = 60; // fps
    private static final int DEFAULT_I_FRAME_INTERVAL = 10; // seconds

    private static final int REPEAT_FRAME_DELAY = 6; // repeat after 6 frames

    private static final int MICROSECONDS_IN_ONE_SECOND = 1_000_000;
    private static final int NO_PTS = -1;

    private final AtomicBoolean rotationChanged = new AtomicBoolean();
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);

    private int bitRate;
    private int frameRate;
    private int iFrameInterval;
    private boolean sendFrameMeta;
    private long ptsOrigin;

    private Surface surface;
    private int width, height;

    public ScreenEncoder(boolean sendFrameMeta,
                         int bitRate,
                         int frameRate,
                         int iFrameInterval,
                         Surface surface,
                         int width,
                         int height) {
        this.sendFrameMeta = sendFrameMeta;
        this.bitRate = bitRate;
        this.frameRate = frameRate == 0 ? DEFAULT_FRAME_RATE : frameRate;
        this.iFrameInterval = iFrameInterval == 0 ? DEFAULT_I_FRAME_INTERVAL : iFrameInterval;

        this.surface = surface;
        this.width = width;
        this.height = height;
    }

    public void onRotationChanged(int rotation) {
        rotationChanged.set(true);
    }

    public boolean consumeRotationChange() {
        return rotationChanged.getAndSet(false);
    }

    public void streamScreen() throws IOException {    // parameter1 - Device device
        MediaFormat format = createFormat(bitRate, frameRate, iFrameInterval);
        //device.setRotationListener(this);
        boolean alive;
        try {
            do {
                MediaCodec codec = createCodec();
                // IBinder display = createDisplay();
                Rect videoRect = new Rect(0, 0, width, height);
                setSize(format, videoRect.width(), videoRect.height());

                /*
                //Rect contentRect = device.getScreenInfo().getContentRect();
                //Rect videoRect = device.getScreenInfo().getVideoSize().toRect();
                //setSize(format, videoRect.width(), videoRect.height());
                */

                Socket sock = new Socket();
                sock.connect(new InetSocketAddress("192.168.43.19", 5567));
                OutputStream sockOut = sock.getOutputStream();

                configure(codec, format);
                Surface surface = codec.createInputSurface();
                //setDisplaySurface(display, surface, contentRect, videoRect);
                codec.start();
                try {
                    alive = encode(codec, sockOut);
                } finally {
                    codec.stop();
                    //destroyDisplay(display);
                    codec.release();
                    surface.release();
                }
            } while (alive);
        } catch (Exception e) {
            Log.e(Encoder.APP_NAME, "Exception", e);
            //device.setRotationListener(null);
        }
    }

    private boolean encode(MediaCodec codec, OutputStream out) throws IOException {
        boolean eof = false;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (!consumeRotationChange() && !eof) {
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, -1);
            eof = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                if (consumeRotationChange()) {
                    // must restart encoding with new size
                    break;
                }
                if (outputBufferId >= 0) {
                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);

                    if (sendFrameMeta) {
                        writeFrameMeta(out, bufferInfo, codecBuffer.remaining());
                    }

                    Log.e(Encoder.APP_NAME, "Streaming");
                    writeFully(out, codecBuffer);
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        }

        return !eof;
    }

    private void writeFrameMeta(OutputStream out, MediaCodec.BufferInfo bufferInfo, int packetSize) throws IOException {
        headerBuffer.clear();

        long pts;
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            pts = NO_PTS; // non-media data packet
        } else {
            if (ptsOrigin == 0) {
                ptsOrigin = bufferInfo.presentationTimeUs;
            }
            pts = bufferInfo.presentationTimeUs - ptsOrigin;
        }

        headerBuffer.putLong(pts);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        writeFully(out, headerBuffer);
    }

    private static MediaCodec createCodec() throws IOException {
        return MediaCodec.createEncoderByType("video/avc");
    }

    private static MediaFormat createFormat(int bitRate, int frameRate, int iFrameInterval) throws IOException {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, "video/avc");
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
        // display the very first frame, and recover from bad quality when no new frames
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, MICROSECONDS_IN_ONE_SECOND * REPEAT_FRAME_DELAY / frameRate); // µs
        return format;
    }

    /*
    private static IBinder createDisplay() {
        return SurfaceControl.createDisplay("scrcpy", true);
    }
    */

    private static void configure(MediaCodec codec, MediaFormat format) {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private static void setSize(MediaFormat format, int width, int height) {
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
    }

    /*
    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect) {
        //SurfaceControl.openTransaction();
        try {
            //SurfaceControl.setDisplaySurface(display, surface);
            //SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect);
            //SurfaceControl.setDisplayLayerStack(display, 0);
        } finally {
            //SurfaceControl.closeTransaction();
        }
    }

    private static void destroyDisplay(IBinder display) {
        //SurfaceControl.destroyDisplay(display);
    }
    */

    public static void writeFully(OutputStream out, ByteBuffer from) throws IOException {
        // ByteBuffer position is not updated as expected by Os.write() on old Android versions, so
        // count the remaining bytes manually.
        // See <https://github.com/Genymobile/scrcpy/issues/291>.

        out.write(from.array());
        /*
        int remaining = from.remaining();
        while (remaining > 0) {
            try {
                int w = Os.write(fd, from);
                if (BuildConfig.DEBUG && w < 0) {
                    // w should not be negative, since an exception is thrown on error
                    throw new AssertionError("Os.write() returned a negative value (" + w + ")");
                }
                remaining -= w;
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EINTR) {
                    throw new IOException(e);
                }
            }
        }
        */
    }

    public static void writeFully(OutputStream out, byte[] buffer, int offset, int len) throws IOException {
        writeFully(out, ByteBuffer.wrap(buffer, offset, len));
    }
}