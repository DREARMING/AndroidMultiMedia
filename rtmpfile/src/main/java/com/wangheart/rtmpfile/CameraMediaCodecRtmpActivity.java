package com.wangheart.rtmpfile;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Toast;

import com.wangheart.rtmpfile.device.CameraController;
import com.wangheart.rtmpfile.ffmpeg.FFmpegHandle;
import com.wangheart.rtmpfile.flv.FlvPacker;
import com.wangheart.rtmpfile.flv.Packer;
import com.wangheart.rtmpfile.rtmp.RtmpHandle;
import com.wangheart.rtmpfile.utils.LogUtils;
import com.wangheart.rtmpfile.view.MySurfaceView;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Author : eric
 * CreateDate : 2017/11/6  10:57
 * Email : ericli_wang@163.com
 * Version : 2.0
 * Desc :
 * Modified :
 */

public class CameraMediaCodecRtmpActivity extends Activity implements SurfaceHolder.Callback {
    private MySurfaceView sv;
    private final int WIDTH = 480;
    private final int HEIGHT = 320;
    private SurfaceHolder mHolder;
    private String url = "rtmp://192.168.1.125/live/test";
    //采集到每帧数据时间
    long previewTime = 0;
    //每帧开始编码时间
    long encodeTime = 0;
    //采集数量
    int count = 0;
    //编码数量
    int encodeCount = 0;
    //采集数据回调
    private StreamIt mStreamIt;
    private MediaCodec mMediaCodec;
    private static final String VCODEC_MIME = "video/avc";
    private final String DATA_DIR = Environment.getExternalStorageDirectory() + File.separator + "AndroidVideo";
    private FlvPacker mFlvPacker;
    private final int FRAME_RATE = 15;
    private OutputStream mOutStream;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        init();
    }

    ExecutorService pushExecutor = Executors.newSingleThreadExecutor();

    private void init() {
        FFmpegHandle.getInstance().initVideo(url,WIDTH,HEIGHT);
        sv = findViewById(R.id.sv);
        initMediaCodec();
        mFlvPacker = new FlvPacker();
        mFlvPacker.initVideoParams(WIDTH, HEIGHT, FRAME_RATE);
        mFlvPacker.setPacketListener(new Packer.OnPacketListener() {
            @Override
            public void onPacket(final byte[] data, final int packetType) {
                pushExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        int ret = RtmpHandle.getInstance().push(data, data.length);
                        LogUtils.w("type：" + packetType + "  length:" + data.length + "  推流结果:" + ret);
                    }
                });
            }
        });
        mStreamIt = new StreamIt();
        CameraController.getInstance().open(1);
        Camera.Parameters params = CameraController.getInstance().getParams();
        params.setPictureFormat(ImageFormat.YV12);
        params.setPreviewFormat(ImageFormat.YV12);
        params.setPictureSize(WIDTH, HEIGHT);
        params.setPreviewSize(WIDTH, HEIGHT);
        params.setPreviewFpsRange(15000, 20000);
        List<String> focusModes = params.getSupportedFocusModes();
        if (focusModes.contains("continuous-video")) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }
        CameraController.getInstance().resetParams(params);
        mHolder = sv.getHolder();
        mHolder.addCallback(this);

    }

    private void initMediaCodec() {
        int bitrate = 2 * WIDTH * HEIGHT * FRAME_RATE / 20;
        try {
            MediaCodecInfo mediaCodecInfo = selectCodec(VCODEC_MIME);
            if (mediaCodecInfo == null) {
                Toast.makeText(this, "mMediaCodec null", Toast.LENGTH_LONG).show();
                throw new RuntimeException("mediaCodecInfo is Empty");
            }
            LogUtils.w("MediaCodecInfo " + mediaCodecInfo.getName());
            mMediaCodec = MediaCodec.createByCodecName(mediaCodecInfo.getName());
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(VCODEC_MIME, WIDTH, HEIGHT);
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mMediaCodec.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            //是否是编码器
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            LogUtils.w(Arrays.toString(types));
            for (String type : types) {
                LogUtils.e("equal " + mimeType.equalsIgnoreCase(type));
                if (mimeType.equalsIgnoreCase(type)) {
                    LogUtils.e("codecInfo " + codecInfo.getName());
                    return codecInfo;
                }
            }
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        FFmpegHandle.getInstance().close();
        CameraController.getInstance().close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mHolder != null) {
            CameraController.getInstance().startPreview(mHolder, mStreamIt);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CameraController.getInstance().stopPreview();
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mFlvPacker.start();
//        mOutStream = IOUtils.open(DATA_DIR + File.separator + "/easy.flv", true);
        CameraController.getInstance().startPreview(mHolder, mStreamIt);
        pushExecutor.execute(new Runnable() {
            @Override
            public void run() {
                int ret = RtmpHandle.getInstance().connect("rtmp://192.168.1.125/live");
                LogUtils.w("打开RTMP连接: " + ret);
            }
        });
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mFlvPacker.stop();
        CameraController.getInstance().stopPreview();
        CameraController.getInstance().close();
        int ret = RtmpHandle.getInstance().close();
        LogUtils.w("关闭RTMP连接：" + ret);
//        IOUtils.close(mOutStream);
    }

    ExecutorService executor = Executors.newSingleThreadExecutor();

    public void btnStart(View view) {
    }

    public class StreamIt implements Camera.PreviewCallback {
        @Override
        public void onPreviewFrame(final byte[] data, Camera camera) {
            long endTime = System.currentTimeMillis();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    encodeTime = System.currentTimeMillis();
                    flvPackage(data);
                    LogUtils.w("编码第:" + (encodeCount++) + "帧，耗时:" + (System.currentTimeMillis() - encodeTime));
                }
            });
            LogUtils.d("采集第:" + (++count) + "帧，距上一帧间隔时间:"
                    + (endTime - previewTime) + "  " + Thread.currentThread().getName());
            previewTime = endTime;
        }
    }


    private void flvPackage(byte[] buf) {
        final int LENGTH = HEIGHT * WIDTH;
        //YV12数据转化成COLOR_FormatYUV420Planar
        LogUtils.d(LENGTH + "  " + (buf.length - LENGTH));
        for (int i = LENGTH; i < (LENGTH + LENGTH / 4); i++) {
            byte temp = buf[i];
            buf[i] = buf[i + LENGTH / 4];
            buf[i + LENGTH / 4] = temp;
//            char x = 128;
//            buf[i] = (byte) x;
        }
        ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
        ByteBuffer[] outputBuffers = mMediaCodec.getOutputBuffers();
        try {
            //查找可用的的input buffer用来填充有效数据
            int bufferIndex = mMediaCodec.dequeueInputBuffer(-1);
            if (bufferIndex >= 0) {
                //数据放入到inputBuffer中
                ByteBuffer inputBuffer = inputBuffers[bufferIndex];
                inputBuffer.clear();
                inputBuffer.put(buf, 0, buf.length);
                //把数据传给编码器并进行编码
                mMediaCodec.queueInputBuffer(bufferIndex, 0,
                        inputBuffers[bufferIndex].position(),
                        System.nanoTime() / 1000, 0);
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

                //输出buffer出队，返回成功的buffer索引。
                int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 0);
                while (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                    //进行flv封装
                    mFlvPacker.onVideoData(outputBuffer, bufferInfo);
                    mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                    outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 0);
                }
            } else {
                LogUtils.w("No buffer available !");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
