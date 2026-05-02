package eu.hyperhdr.android.capture

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val VERTEX_SRC = """#version 300 es
in vec4 aPos;
in vec2 aTex;
uniform mat4 uTexTransform;
out vec2 vTex;
void main() { gl_Position = aPos; vTex = (uTexTransform * vec4(aTex, 0.0, 1.0)).xy; }
"""

private const val FRAGMENT_SRC = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
in vec2 vTex;
out vec4 fragColor;

vec3 pqEotf(vec3 e) {
    // SMPTE ST 2084 inverse EOTF, normalized to [0,1] = 0..10000 cd/m².
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    vec3 ep = pow(max(e, 0.0), vec3(1.0 / m2));
    vec3 num = max(ep - c1, 0.0);
    vec3 den = c2 - c3 * ep;
    return pow(num / den, vec3(1.0 / m1));
}

vec3 hable(vec3 x) {
    const float A = 0.15, B = 0.50, C = 0.10, D = 0.20, E = 0.02, F = 0.30;
    return ((x*(A*x+C*B)+D*E)/(x*(A*x+B)+D*F))-E/F;
}

const mat3 BT2020_TO_BT709 = mat3(
    1.6605, -0.1246, -0.0182,
   -0.5876,  1.1329, -0.1006,
   -0.0728, -0.0083,  1.1187
);

void main() {
    vec3 e = texture(uTex, vTex).rgb;
    // Inverse PQ → linear, normalized so 1.0 ≈ 100 cd/m² SDR reference.
    vec3 linear = pqEotf(e) / 100.0;
    // Hable tone-map. White point chosen to map ~100 (1.0 here) to display white.
    vec3 mapped = hable(linear) / hable(vec3(11.2));
    // BT.2020 → BT.709 in linear light.
    vec3 rgb709 = clamp(BT2020_TO_BT709 * mapped, 0.0, 1.0);
    // Re-apply approximate sRGB gamma so the downstream BT.601 pack matches SDR pipeline.
    vec3 srgb = pow(rgb709, vec3(1.0 / 2.2));
    fragColor = vec4(srgb, 1.0);
}
"""

class HdrToneMapShader {
    val program = ShaderProgram(VERTEX_SRC, FRAGMENT_SRC)
    private val aPos = program.attrib("aPos")
    private val aTex = program.attrib("aTex")
    private val uTex = program.uniform("uTex")
    private val uTexTransform = program.uniform("uTexTransform")

    private val quadVerts: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            )); position(0)
        }
    private val identity = floatArrayOf(
        1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f,
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
