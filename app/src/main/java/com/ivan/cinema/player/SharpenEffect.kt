package com.ivan.cinema.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * 画质增强：边缘自适应锐化。
 *
 * 走 Media3 的视频后处理链（`ExoPlayer.setVideoEffects`）—— 着色器跑在解码之后的
 * GL 管线里，是真正的逐帧处理，不是给 View 套一层滤镜。
 *
 * 着色器逻辑见 `assets/sharpen_fragment.glsl`：四邻域均值求差分，再用边缘门限把
 * 平色区的权重压到 0，只锐化真正的边缘。动画是大面积平色 + 强边缘，这个取向正好。
 *
 * ⚠️ 必须当成"可能失败"的功能：视频后处理要解码器输出到 GL、部分设备/编码格式
 * 走不通，开了可能直接黑屏或掉帧。调用方 [com.ivan.cinema.player.PlayerActivity]
 * 全程 runCatching，失败就退回关闭状态并提示，绝不让它把播放搞坏。
 */
@UnstableApi
class SharpenEffect(
    /** 锐化强度。1.0 已经比较明显，默认给 0.9，再高会在压缩差的片上出白边。 */
    private val amount: Float = 0.9f
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        SharpenShaderProgram(context, useHdr, amount)
}

@UnstableApi
private class SharpenShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val amount: Float
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val program = GlProgram(
        context,
        "sharpen_vertex.glsl",
        "sharpen_fragment.glsl"
    ).apply {
        setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        setBufferAttribute(
            "aTexSamplingCoordinate",
            GlUtil.getTextureCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    private var texelWidth = 1f / 1920f
    private var texelHeight = 1f / 1080f

    /**
     * 返回**输入尺寸**：这一步只做锐化，不改变分辨率 —— 放大交给播放器自己的缩放器。
     * 在这里先放大再锐化会多一次全分辨率纹理往返，手机 GPU 上不划算。
     */
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelWidth = 1f / inputWidth.coerceAtLeast(1)
        texelHeight = 1f / inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            // 方法名按 media3-common 1.3.1 的实际签名（用 javap 从 aar 里核过，
            // 不是 setSamplerTexId/bindAttributes）
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            program.setFloatUniform("uTexelWidth", texelWidth)
            program.setFloatUniform("uTexelHeight", texelHeight)
            program.setFloatUniform("uAmount", amount)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        runCatching { program.delete() }
    }
}
