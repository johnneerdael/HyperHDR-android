package eu.hyperhdr.android.capture

import android.opengl.GLES20
import android.opengl.GLES30

class ShaderProgram(vertexSrc: String, fragmentSrc: String) {
    val handle: Int = GLES20.glCreateProgram().also { p ->
        check(p != 0) { "glCreateProgram failed" }
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            error("glLinkProgram failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    fun use() { GLES20.glUseProgram(handle) }
    fun release() { GLES20.glDeleteProgram(handle) }
    fun uniform(name: String): Int = GLES20.glGetUniformLocation(handle, name)
    fun attrib(name: String): Int = GLES20.glGetAttribLocation(handle, name)

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            error("glCompileShader failed: $log\nsrc:\n$src")
        }
        return s
    }
}
