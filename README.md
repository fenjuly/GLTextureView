# GLTextureView
 An easy-to-use TextureView with GL context

This GLTextureView is different from other GLTextureView whose implementation is copied from GLSurfaceView.

### Advantage

* The code is very simplified, easy to understand for developer
* there is no ```Lock``` between **Main Thread** And **GL Thread**, so you don't need to worry about the dead lock issue while using ```OnPause``` method

### Usage
While View is attached, the **GL Thread** will be created, and will be destroyed when View is detached.

if you want to wirte your render code, just implements the interface ```GLTextureView.Renderer```, it has four methods which is the same as ```GLSurfaceView.Renderer```

```
interface Renderer {
        fun onSurfaceCreated(gl: GL10, config: EGLConfig)
        fun onSurfaceChanged(gl: GL10, width: Int, height: Int)
        fun onDrawFrame(gl: GL10): Boolean
        fun onSurfaceDestroyed()
    }
```

Enjoy it ^_^
