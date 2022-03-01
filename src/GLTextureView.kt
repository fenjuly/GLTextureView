package com.fenjuly.demo

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.AttributeSet
import android.view.TextureView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

open class GLTextureView: TextureView, TextureView.SurfaceTextureListener{

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    private var glHandler: Handler? = null
    private var glHandlerThread: HandlerThread? = null


    private var renderer: Renderer? = null

    private var paused = false

    private var detachDone = true
    private var needDoPendingAttach = false

    var gLEnvCallback: GLEnvCallback? = null

    private val envCreateListeners = mutableListOf<GLEnvCallback>()

    init {
        surfaceTextureListener = this
    }

    // to resolve thread safety issue while onAttach/onDetach was invoked
    private val glOperationLock = Any()

    fun setRenderer(renderer: Renderer) {
        this.renderer = renderer
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        initGL(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        notifySurfaceSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }


    private fun initGL(surface: SurfaceTexture, width: Int, height: Int) {
        if (paused) return
        glHandler?.let {
            val msg = it.obtainMessage(MSG_INIT_GL)
            msg.arg1 = width
            msg.arg2 = height
            msg.obj = surface
            it.sendMessage(msg)
        }
    }

    private fun notifySurfaceSizeChanged(width: Int, height: Int) {
        glHandler?.let {
            val msg = it.obtainMessage(MSG_SIZE_CHANGED)
            msg.arg1 = width
            msg.arg2 = height
            it.sendMessage(msg)
        }
    }

    private fun destroyGL() {
        glHandler?.let {
            val msg = it.obtainMessage(MSG_DESTROY)
            it.sendMessage(msg)
        }
    }

    private fun markDetach() {
        glHandler?.let {
            val msg = it.obtainMessage(MSG_DETACH_DONE)
            it.sendMessage(msg)
        }
    }

    fun requestRender() {
        glHandler?.let {
            it.removeMessages(MSG_DRAW_FRAME)
            it.sendMessage(it.obtainMessage(MSG_DRAW_FRAME))
        }
    }

    fun queueEvent(r: Runnable) {
        glHandler?.let {
            val msg = it.obtainMessage(MSG_DO_EVENT)
            msg.obj = r
            it.sendMessage(msg)
        }
    }

    open fun onPause() {
        paused = true
        destroyGL()
    }

    open fun onResume() {
        paused = false
        tryInitGL()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (glHandler != null) throw IllegalStateException("glHandler should be null, check what's wrong")

        if (!detachDone) {
            needDoPendingAttach = true
        } else {
            generateGLThread()
            needDoPendingAttach = false
        }

    }

    private fun tryInitGL() {
        if (width != 0 && height != 0 && surfaceTexture != null) {
            initGL(surfaceTexture!!, width, height)
        }
    }

    fun glEnvReady(): Boolean {
        return glHandler != null
    }

    fun addEnvCreateListeners(envCreateListener: GLEnvCallback) {
        envCreateListeners.add(envCreateListener)
    }

    private fun generateGLThread() {
        val code = hashCode()
        glHandlerThread = HandlerThread("DCGLTextureView-$code-Thread")
        glHandlerThread?.start()
        glHandler = Handler(glHandlerThread!!.looper, DCGLHandlerCallback(glOperationLock, { renderer }) {
            detachDone = true
            if (needDoPendingAttach) {
                generateGLThread()
                needDoPendingAttach = false
                tryInitGL()
            }
        })
        gLEnvCallback?.onGLEnvCreated(this)
        while (envCreateListeners.isNotEmpty()) {
            envCreateListeners.removeAt(0).onGLEnvCreated(this)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        detachDone = false
        destroyGL()
        markDetach()
        glHandlerThread?.quitSafely()
        glHandlerThread = null
        glHandler = null
        needDoPendingAttach = false
    }

    fun getGLThreadId(): Int {
        return glHandlerThread?.threadId?: -1
    }



    interface Renderer {
        fun onSurfaceCreated(gl: GL10, config: EGLConfig)
        fun onSurfaceChanged(gl: GL10, width: Int, height: Int)
        fun onDrawFrame(gl: GL10): Boolean
        fun onSurfaceDestroyed()
    }

    interface GLEnvCallback {
        fun onGLEnvCreated(v: GLTextureView)
    }


    companion object {

        private const val MSG_INIT_GL = 1
        private const val MSG_SIZE_CHANGED = 2
        private const val MSG_DRAW_FRAME = 3
        private const val MSG_DESTROY = 4
        private const val MSG_DO_EVENT = 5
        private const val MSG_DETACH_DONE = 6

    }


    class DCGLHandlerCallback(private val lock: Any, private val rDelegate: () -> Renderer?, private val detachDoneRunnable: Runnable): Handler.Callback {

        private var surfaceCreated = false
        private val eglHelper = EGLHelper()
        private val mainHandler = Handler(Looper.getMainLooper())

        private val renderer: Renderer?
            get() = rDelegate.invoke()


        override fun handleMessage(msg: Message): Boolean {
            synchronized(lock) {
                when(msg.what) {
                    MSG_INIT_GL -> {
                        if (surfaceCreated){
                            return true
                        }
                        val width = msg.arg1
                        val height = msg.arg2
                        eglHelper.setSurfaceType(EGLHelper.SURFACE_WINDOW, msg.obj)
                        eglHelper.eglInit(width, height)
                        surfaceCreated = true
                        renderer?.onSurfaceCreated(eglHelper.mGL, eglHelper.mEglConfig)
                        renderer?.onSurfaceChanged(eglHelper.mGL, width, height)
                        renderer?.onDrawFrame(eglHelper.mGL)
                    }
                    MSG_SIZE_CHANGED -> {
                        if (!surfaceCreated) {
                            throw IllegalStateException("MSG_SIZE_CHANGED surface isn't created")
                            return true
                        }
                        val width = msg.arg1
                        val height = msg.arg2
                        renderer?.onSurfaceChanged(eglHelper.mGL, width, height)
                    }
                    MSG_DRAW_FRAME -> {
                        if (!surfaceCreated) {
                            return true
                        }
                        renderer?.onDrawFrame(eglHelper.mGL)
                        eglHelper.swap()
                    }
                    MSG_DESTROY -> {
                        if (!surfaceCreated){
                            return true
                        }
                        renderer?.onSurfaceDestroyed()
                        eglHelper.destroy()
                        surfaceCreated = false
                    }
                    MSG_DO_EVENT -> {
                        val runnable = msg.obj as Runnable
                        runnable.run()
                    }
                    MSG_DETACH_DONE -> {
                        mainHandler.post(detachDoneRunnable)
                    }
                }
                return true
            }
        }
    }

}