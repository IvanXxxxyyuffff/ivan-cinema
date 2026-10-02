// Media3 的 GlProgram 要求着色器以 assets 文件路径传入，所以这里放成独立文件。

attribute vec4 aFramePosition;
attribute vec4 aTexSamplingCoordinate;
varying vec2 vTexSamplingCoordinate;

void main() {
    gl_Position = aFramePosition;
    vTexSamplingCoordinate = aTexSamplingCoordinate.xy;
}
