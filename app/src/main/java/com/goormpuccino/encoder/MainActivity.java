package com.goormpuccino.encoder;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;


public class MainActivity extends AppCompatActivity {
    private MediaProjectionManager mMediaProjectionManager;
    private MediaProjection mMediaProjection;
    private static final int REQUEST_CODE_CAPTURE_PERM = 9797;
    private WritableByteChannel outChannel = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.e(Encoder.APP_NAME, "\n\nEncoder App Started");

        mMediaProjectionManager = (MediaProjectionManager)getSystemService(
                android.content.Context.MEDIA_PROJECTION_SERVICE);

        Intent permissionIntent = mMediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(permissionIntent, REQUEST_CODE_CAPTURE_PERM);

        Runnable sSocket = new startSocket();
        Thread sThread = new Thread(sSocket);
        sThread.start();

//        mEncoder = new EncoderThread();
  //      mEncoder.start();

        /*
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_fullscreen);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        this.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        SurfaceView sv = findViewById(R.id.surfaceView);
        sv.getHolder().addCallback(this);
        */
    }

    private class startSocket implements Runnable {
        @Override
        public void run() {
            try {
                Socket sock = new Socket();
                sock.setTcpNoDelay(true);
                sock.connect(new InetSocketAddress("192.168.1.45", 5567));
                sock.setTcpNoDelay(true);
                OutputStream sockOut = sock.getOutputStream();
                outChannel = Channels.newChannel(sockOut);
            } catch (Exception e) {
                Log.e(Encoder.APP_NAME, "Socket err", e);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (REQUEST_CODE_CAPTURE_PERM == requestCode) {
            if (resultCode == RESULT_OK) {
                mMediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);

                Runnable sEncoding = new recordingClass();
                Thread sThread = new Thread(sEncoding);
                sThread.start();

            } else {
                // user did not grant permissions
            }
        }
    }

    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static int VIDEO_WIDTH = 1080;
    private static int VIDEO_HEIGHT = 1920;

    private Surface mInputSurface;
    private MediaCodec mVideoEncoder;
    private MediaCodec.BufferInfo mVideoBufferInfo;
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);
    private long ptsOrigin;

    private HandlerThread handlerThread = new HandlerThread("DrainThread");
    private Handler mDrainHandler = null;
    private Runnable mDrainEncoderRunnable = new Runnable() {
        @Override
        public void run() {
            drainEncoder();
        }
    };
// …

    private class recordingClass implements Runnable {
        @Override
        public void run() {
            handlerThread.start();
            mDrainHandler = new Handler(handlerThread.getLooper());

            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (defaultDisplay == null) {
                throw new RuntimeException("No display found.");
            }
            prepareVideoEncoder();

            // Get the display size and density.
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            VIDEO_WIDTH = 1080;//metrics.widthPixels;
            VIDEO_HEIGHT = 1920;//metrics.heightPixels;
            int screenDensity = metrics.densityDpi;

            Log.e(Encoder.APP_NAME, "Width: " + VIDEO_WIDTH + ", height: " + VIDEO_HEIGHT);

            // Start the video input.
            mMediaProjection.createVirtualDisplay("Recording Display",
                    VIDEO_WIDTH,
                    VIDEO_HEIGHT,
                    screenDensity, 0 /* flags */, mInputSurface,
                    null /* callback */, null /* handler */);

            // Start the encoders
            drainEncoder();
        }
    }

    private static final int MICROSECONDS_IN_ONE_SECOND = 1_000_000;
    private static final int REPEAT_FRAME_DELAY = 6; // repeat after 6 frames

    private void prepareVideoEncoder() {
        mVideoBufferInfo = new MediaCodec.BufferInfo();
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT);
        int frameRate = 60; // 30 fps

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setString(MediaFormat.KEY_MIME, "video/avc");
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 15000000); // 6Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        //format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, -10); // 1 seconds between I-frames
//        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10); // 1 seconds between I-frames
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, MICROSECONDS_IN_ONE_SECOND * REPEAT_FRAME_DELAY / frameRate); // µs
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
//            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoEncoder.createInputSurface();
            mVideoEncoder.start();
        } catch (IOException e) {
            releaseEncoders();
        }
    }

    private boolean drainEncoder() {
        mDrainHandler.removeCallbacks(mDrainEncoderRunnable);

        while (true) {
            int bufferIndex = mVideoEncoder.dequeueOutputBuffer(mVideoBufferInfo, 0);

            if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // nothing available yet
                break;
            } else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
            } else if (bufferIndex < 0) {
                // not sure what's going on, ignore it
            } else {
                ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(bufferIndex);
                if (encodedData == null) {
                    throw new RuntimeException("couldn't fetch buffer at index " + bufferIndex);
                }

                if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    mVideoBufferInfo.size = 0;
                }

                //writeFrameMeta(mVideoBufferInfo, encodedData.remaining());
                Log.e(Encoder.APP_NAME, "Streaming");
                writeFully(encodedData);

                mVideoEncoder.releaseOutputBuffer(bufferIndex, false);

                if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }

        mDrainHandler.postDelayed(mDrainEncoderRunnable, 10);
        return false;
    }

    private void writeFrameMeta(MediaCodec.BufferInfo bufferInfo, int packetSize) {
        headerBuffer.clear();

        long pts;
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            pts = 0; // non-media data packet
        } else {
            if (ptsOrigin == 0) {
                ptsOrigin = bufferInfo.presentationTimeUs;
            }
            pts = bufferInfo.presentationTimeUs - ptsOrigin;
        }

        headerBuffer.putLong(pts);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        writeFully(headerBuffer);
    }

    public void writeFully(ByteBuffer from) {
        try {
            outChannel.write(from);
        } catch (Exception e) {
            Log.e(Encoder.APP_NAME, "Write exception", e);
        }
    }

    private void releaseEncoders() {
        mDrainHandler.removeCallbacks(mDrainEncoderRunnable);
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        mVideoBufferInfo = null;
        mDrainEncoderRunnable = null;
    }
}
