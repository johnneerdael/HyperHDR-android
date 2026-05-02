package eu.hyperhdr.android.capture

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val VERTEX_SRC = """#version 300 es
in vec4 aPos;
in vec2 aTex;
out vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = aTex;
}
"""

// Two passes: outputs are Y (R8) and UV (R8G8).
private const val Y_FRAGMENT_SRC = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vTex;
out float oY;
void main() {
    vec3 rgb = texture(uTex, vTex).rgb;
    // BT.601 limited-range Y: 16..235
    oY = (0.257 * rgb.r + 0.504 * rgb.g + 0.098 * rgb.b) + 0.0625;
}
"""

private const val UV_FRAGMENT_SRC = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vTex;
out vec2 oUV;
void main() {
    vec3 rgb = texture(uTex, vTex).rgb;
    // BT.601 limited-range Cb,Cr: 16..240, centered at 128.
    float u = -0.148 * rgb.r - 0.291 * rgb.g + 0.439 * rgb.b + 0.5;
    float v =  0.439 * rgb.r - 0.368 * rgb.g - 0.071 * rgb.b + 0.5;
    oUV = vec2(u, v);
}
"""

class Nv12PackProgram {
    private val yProgram = ShaderProgram(VERTEX_SRC, Y_FRAGMENT_SRC)
    private val uvProgram = ShaderProgram(VERTEX_SRC, UV_FRAGMENT_SRC)

    private val quadVerts: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }

    /** Renders the Y plane at [width]×[height] from [rgbaTextureId]. Caller has bound the Y FBO. */
    fun drawY(rgbaTextureId: Int) = drawWith(yProgram, rgbaTextureId)

    /** Renders the UV plane at [width/2]×[height/2] from [rgbaTextureId]. Caller has bound the UV FBO. */
    fun drawUV(rgbaTextureId: Int) = drawWith(uvProgram, rgbaTextureId)

    private fun drawWith(p: ShaderProgram, tex: Int) {
        p.use()
        val aPos = p.attrib("aPos"); val aTex = p.attrib("aTex"); val uTex = p.uniform("uTex")
        quadVerts.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quadVerts)
        GLES20.glEnableVertexAttribArray(aPos)
        quadVerts.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quadVerts)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() { yProgram.release(); uvProgram.release() }
}
