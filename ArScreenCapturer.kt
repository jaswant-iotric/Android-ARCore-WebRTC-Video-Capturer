import package_abc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.*
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RequiresApi(api = Build.VERSION_CODES.N)
class ArScreenCapturer(view: SurfaceView, framePerSecond: Int) : VideoCapturer, VideoSink {
    private var width = 0
    private var height = 0
    private val view: SurfaceView
    private var context: Context? = null
    private var capturerObserver: CapturerObserver? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var isDisposed = false
    private var viewBitmap: Bitmap? = null
    private val handlerPixelCopy = Handler(Looper.getMainLooper())
    private var handler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    val numCapturedFrames: Long = 0
    private val yuvConverter = YuvConverter()
    private var buffer: TextureBufferImpl? = null
    private val start = System.nanoTime()
    private var frameSenderJob: Job? = null
    private var isStarted = true
    private val handlerThread = HandlerThread(ArSessionActivity::class.java.simpleName)
    private fun checkNotDisposed() {
        if (isDisposed) {
            throw RuntimeException(context?.getString(R.string.capture_is_disposed))
        }
    }

    fun stopStream() {
        frameSenderJob?.cancel()
        try {
            handlerThread.quitSafely()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        isStarted = false
    }

    @Synchronized
    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper, context: Context, capturerObserver: CapturerObserver) {
        checkNotDisposed()
        if (capturerObserver == null) {
            throw RuntimeException(context.getString(R.string.captue_observer_not_set))
        } else {
            this.context = context
            this.capturerObserver = capturerObserver
            if (surfaceTextureHelper == null) {
                throw RuntimeException(context.getString(R.string.surface_texture_helper_not_set))
            } else {
                this.surfaceTextureHelper = surfaceTextureHelper
            }
        }

        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }

    override fun startCapture(width: Int, height: Int, fps: Int) {
        checkNotDisposed()
        started.set(true)
        this.width = width
        this.height = height
        capturerObserver!!.onCapturerStarted(true)
        surfaceTextureHelper!!.startListening(this)

        frameSenderJob = CoroutineScope(Dispatchers.IO).launch {
            while (isStarted) {
                try {
                    var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(view, bitmap, { copyResult: Int ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            /*viewBitmap = getResizedBitmap(bitmap)
                            if (viewBitmap != null) {
                                sendToServer(viewBitmap!!, yuvConverter, start)
                                try {
                                    viewBitmap?.recycle()
                                    viewBitmap = null
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                try {
                                    bitmap?.recycle()
                                    bitmap = null
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }*/
                            sendArView(bitmap)
                            bitmap?.recycle()
                        } else {
                            Log.e("Pixel_copy-->", "Couldn't create bitmap of the SurfaceView")
                        }
                        //   handlerThread.quitSafely()
                    }, handler)
                } catch (e: Exception) {
                    if (!ArSessionActivity.active) {
                        isStarted = false
                    }
                    e.printStackTrace()
                }
                delay(VIEW_CAPTURER_FRAMERATE_MS.toLong())
            }
        }
    }

    companion object {
        private var VIEW_CAPTURER_FRAMERATE_MS = 15
    }

    private fun createFlippedBitmap(source: Bitmap?, xFlip: Boolean, yFlip: Boolean): Bitmap? {
        return try {
            val matrix = Matrix()
            matrix.postScale(-1f, 1f, source!!.width / 2f, source.height / 2f)
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (e: Exception) {
            null
        }
    }

    private fun sendToServer(bitmap: Bitmap, yuvConverter: YuvConverter, start: Long) {
        try {
            val textures = IntArray(1)
            GLES20.glGenTextures(0, textures, 0)
            buffer = TextureBufferImpl(width, height, VideoFrame.TextureBuffer.Type.RGB, textures[0], Matrix(), surfaceTextureHelper!!.handler, yuvConverter, null)
            val flippedBitmap = createFlippedBitmap(bitmap, true, false)
            surfaceTextureHelper!!.handler.post {
                if (flippedBitmap != null) {
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, flippedBitmap, 0)
                    val i420Buf = yuvConverter.convert(buffer)
                    val frameTime = System.nanoTime() - start
                    val videoFrame = VideoFrame(i420Buf, 180, frameTime)
                    capturerObserver!!.onFrameCaptured(videoFrame)
                    videoFrame.release()
                    try {
                        flippedBitmap.recycle()
                     //   viewBitmap?.recycle()
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    @Throws(InterruptedException::class)
    override fun stopCapture() {
        checkNotDisposed()
        surfaceTextureHelper!!.stopListening()
        capturerObserver!!.onCapturerStopped()
        started.set(false)
        handler.removeCallbacksAndMessages(null)
        handlerPixelCopy.removeCallbacksAndMessages(null)
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        checkNotDisposed()
        this.width = width
        this.height = height
    }

    override fun dispose() {
        isDisposed = true
    }

    override fun isScreencast(): Boolean {
        return true
    }

    /**
     * reduces the size of the image
     *
     * @param image
     * @param maxSize
     * @return
     */
    private fun getResizedBitmap(image: Bitmap): Bitmap? {
        val width = image.width
        val height = image.height
        val bitmap = Bitmap.createScaledBitmap(image, width, height, true)
        //  bitmap.recycle()
        return bitmap
    }

    override fun onFrame(videoFrame: VideoFrame) {}

    init {
        require(framePerSecond > 0) { "framePersecond must be greater than 0" }
        this.view = view
        val tmp = 1f / framePerSecond * 1000
        VIEW_CAPTURER_FRAMERATE_MS = Math.round(tmp)
    }

    fun sendFrameExternal(arSurfaceView: SurfaceView) {
        width = arSurfaceView.width
        height = arSurfaceView.height
        val outBitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
        PixelCopy.request(arSurfaceView, outBitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                     sendToServer(outBitmap, yuvConverter, start)
                   // sendArView(outBitmap)
                     outBitmap.recycle()
                }
            }, handler
        )
    }

    fun sendArView(arBitmap: Bitmap?) {
        if (arBitmap == null) return

        val argb = IntArray(arBitmap.width * arBitmap.height)

        arBitmap.getPixels(argb, 0, arBitmap.width, 0, 0, arBitmap.width, arBitmap.height)

        val yuv = ByteArray(arBitmap.width * arBitmap.height * 3 / 2)
      //  val nv21 = yuv420toNV21(yuv, arBitmap.width, arBitmap.height)
        encodeYUV420SP(yuv, argb, arBitmap.width, arBitmap.height)

        val timestampNS = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
        val buffer = NV21Buffer(yuv, arBitmap.width, arBitmap.height, null)

        val videoFrame = VideoFrame(buffer, 0, timestampNS)
        sendToServer(videoFrame)

        arBitmap.recycle()
    }

    private fun sendToServer(videoFrame: VideoFrame) {
        try {
            surfaceTextureHelper!!.handler.post {
                capturerObserver!!.onFrameCaptured(videoFrame)
                videoFrame.release()
            }
        } catch (ignored: Exception) {
        }
    }

    fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var a: Int
        var R: Int
        var G: Int
        var B: Int
        var Y: Int
        var U: Int
        var V: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                a = argb[index] and -0x1000000 shr 24 // a is not used obviously
                R = argb[index] and 0xff0000 shr 16
                G = argb[index] and 0xff00 shr 8
                B = argb[index] and 0xff shr 0

                // well known RGB to YUV algorithm
                Y = (66 * R + 129 * G + 25 * B + 128 shr 8) + 16
                U = (-38 * R - 74 * G + 112 * B + 128 shr 8) + 128
                V = (112 * R - 94 * G - 18 * B + 128 shr 8) + 128

                // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
                //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
                //    pixel AND every other scanline.
                yuv420sp[yIndex++] = (if (Y < 0) 0 else if (Y > 255) 255 else Y).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (if (V < 0) 0 else if (V > 255) 255 else V).toByte()
                    yuv420sp[uvIndex++] = (if (U < 0) 0 else if (U > 255) 255 else U).toByte()
                }
                index++
            }
        }
    }

    fun yuv420toNV21(data: ByteArray?, width: Int, height: Int): ByteArray? {
        val buffer: JavaI420Buffer = createYUV(data, width, height)!!
        return yuv420toNV21(buffer as VideoFrame.I420Buffer, width, height)
    }

    fun yuv420toNV21(i420: VideoFrame.I420Buffer, width: Int, height: Int): ByteArray? {
        val crop = Rect(0, 0, width, height)
        val format = 35
        val planes: Array<Plane?> =
            arrayOfNulls<Plane>(3)
        val yPlane: Plane =
            Plane(i420.getDataY(), i420.getStrideY(), 1)
        val uPlane: Plane =
            Plane(i420.getDataU(), i420.getStrideU(), 1)
        val vPlane: Plane =
            Plane(i420.getDataV(), i420.getStrideV(), 1)
        planes[0] = yPlane
        planes[1] = uPlane
        planes[2] = vPlane
        val data = ByteArray(width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8)
        val rowData = ByteArray(planes[0]!!.rowStride)
        var channelOffset = 0
        var outputStride = 1
        for (i in planes.indices) {
            when (i) {
                0 -> {
                    channelOffset = 0
                    outputStride = 1
                }
                1 -> {
                    channelOffset = width * height + 1
                    outputStride = 2
                }
                2 -> {
                    channelOffset = width * height
                    outputStride = 2
                }
            }
            val buffer: ByteBuffer = planes[i]!!.buffer
            val rowStride: Int = planes[i]!!.rowStride
            val pixelStride: Int = planes[i]!!.pixelStride
            val shift = if (i == 0) 0 else 1
            val w = width shr shift
            val h = height shr shift
            buffer.position(rowStride * (crop.top shr shift) + pixelStride * (crop.left shr shift))
            for (row in 0 until h) {
                var length: Int
                if (pixelStride == 1 && outputStride == 1) {
                    length = w
                    buffer[data, channelOffset, w]
                    channelOffset += w
                } else {
                    length = (w - 1) * pixelStride + 1
                    buffer[rowData, 0, length]
                    for (col in 0 until w) {
                        data[channelOffset] = rowData[col * pixelStride]
                        channelOffset += outputStride
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length)
                }
            }
        }
        return data
    }

    fun createYUV(data: ByteArray?, width: Int, height: Int): JavaI420Buffer? {
        return if (data != null && data.size != 0) {
            val buffer = JavaI420Buffer.allocate(width, height)
            val dataY = buffer.dataY
            val dataU = buffer.dataU
            val dataV = buffer.dataV
            val chromaHeight = (height + 1) / 2
            val sizeY = height * buffer.strideY
            val sizeU = chromaHeight * buffer.strideU
            val sizeV = chromaHeight * buffer.strideV
            dataY.put(data, 0, sizeY)
            dataU.put(data, sizeY, sizeU)
            dataV.put(data, sizeY + sizeU, sizeV)
            buffer
        } else {
            null
        }
    }

    class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

}
