package eu.hyperhdr.android.capture

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val VERTEX_SRC = """#version 300 es
in vec4 aPos;
in vec2 aTex;
uniform mat4 uTexTransform;
out vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uTexTransform * vec4(aTex, 0.0, 1.0)).xy;
}
"""

private const val FRAGMENT_SRC = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
in vec2 vTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vTex);
}
"""

class DownscaleProgram {
    val program = ShaderProgram(VERTEX_SRC, FRAGMENT_SRC)
    private val aPos = program.attrib("aPos")
    private val aTex = program.attrib("aTex")
    private val uTex = program.uniform("uTex")
    private val uTexTransform = program.uniform("uTexTransform")
    private val quadVerts: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // pos x,y, tex u,v — full-screen quad
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }
    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    fun draw(externalTextureId: Int, texTransform: FloatArray = identity) {
        program.use()
        quadVerts.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quadVerts)
        GLES20.glEnableVertexAttribArray(aPos)
        quadVerts.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quadVerts)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(uTexTransform, 1, false, texTransform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() { program.release() }
}
