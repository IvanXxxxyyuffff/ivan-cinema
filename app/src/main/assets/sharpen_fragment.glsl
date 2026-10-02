// 边缘自适应锐化（unsharp mask + 边缘门限）。
//
// 为什么不是"通用锐化"：动画是大面积平色 + 少数强边缘，全图均匀锐化会把平色区的
// 压缩噪点一起放大，看起来像"颗粒变粗"。所以先用四邻域均值求差分，再用 smoothstep
// 把差分很小的区域（平色）权重压到 0，只对真正的边缘加锐。这一步是"让线条更利"，
// 不是"让画面更假"。
//
// 说明白它做不到什么：它不产生新的细节，不能把 1080p 变成真 4K。
// 真 4K 只能来自片源本身 —— 采集源的 m3u8 最高就是 1080p，且多为单码率。

precision mediump float;

uniform sampler2D uTexSampler;
uniform float uTexelWidth;
uniform float uTexelHeight;
uniform float uAmount;
varying vec2 vTexSamplingCoordinate;

void main() {
    vec2 t = vec2(uTexelWidth, uTexelHeight);
    vec4 c = texture2D(uTexSampler, vTexSamplingCoordinate);
    vec3 n = texture2D(uTexSampler, vTexSamplingCoordinate + vec2(0.0, -t.y)).rgb;
    vec3 s = texture2D(uTexSampler, vTexSamplingCoordinate + vec2(0.0,  t.y)).rgb;
    vec3 w = texture2D(uTexSampler, vTexSamplingCoordinate + vec2(-t.x, 0.0)).rgb;
    vec3 e = texture2D(uTexSampler, vTexSamplingCoordinate + vec2( t.x, 0.0)).rgb;

    vec3 blur = (n + s + w + e) * 0.25;
    vec3 diff = c.rgb - blur;

    // 边缘门限：差分很小时（平色区）权重为 0，避免放大噪点
    float edge = length(diff);
    float gate = smoothstep(0.012, 0.075, edge);

    vec3 sharp = c.rgb + diff * uAmount * gate;
    gl_FragColor = vec4(clamp(sharp, 0.0, 1.0), c.a);
}
