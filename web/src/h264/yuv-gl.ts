// 디코더가 뱉는 YUV420 평면을 WebGL2 로 화면에 올린다.
//
// 2D 캔버스에서 자바스크립트로 YUV→RGB 를 돌리면 1280x720 한 장에 수십 ms 가 든다 — 디코딩보다
// 색변환이 더 비싸지는 일이 실제로 생긴다. 셰이더에 맡기면 사실상 공짜다. 차의 진단에서 WebGL2 는
// O 로 측정됐다(2026-09-14).
//
// 메인 스레드의 <canvas> 에도, 워커로 넘긴 OffscreenCanvas 에도 붙는다(h264/worker.ts). 둘 다 같은
// WebGL2 컨텍스트를 준다.

const VERT = `#version 300 es
in vec2 pos;
out vec2 uv;
void main() {
  uv = pos * 0.5 + 0.5;
  uv.y = 1.0 - uv.y;            // 텍스처는 위에서 아래로, 클립 좌표는 아래에서 위로
  gl_Position = vec4(pos, 0.0, 1.0);
}`;

// 인코더가 KEY_COLOR_RANGE 를 LIMITED 로 설정하므로(H264Encoder.java) BT.601 limited 계수를 쓴다.
const FRAG = `#version 300 es
precision mediump float;
in vec2 uv;
uniform sampler2D texY;
uniform sampler2D texU;
uniform sampler2D texV;
out vec4 color;
void main() {
  float y = (texture(texY, uv).r - 0.0625) * 1.164;
  float u = texture(texU, uv).r - 0.5;
  float v = texture(texV, uv).r - 0.5;
  color = vec4(y + 1.596 * v, y - 0.391 * u - 0.813 * v, y + 2.018 * u, 1.0);
}`;

export type YuvCanvas = HTMLCanvasElement | OffscreenCanvas;

export class YuvGl {
  private gl: WebGL2RenderingContext;
  private tex: [WebGLTexture, WebGLTexture, WebGLTexture];
  private w = 0;
  private h = 0;

  /** @throws 컨텍스트나 셰이더를 만들 수 없을 때 — 호출자가 lastError 로 올린다. */
  constructor(private readonly canvas: YuvCanvas) {
    // desynchronized: 컴포지터의 큐를 건너뛰고 바로 화면으로 — 지원하는 곳에서는 한 프레임쯤 지연이 준다.
    // 안 되는 곳에서는 무시되는 힌트일 뿐이다.
    const gl = canvas.getContext('webgl2', { alpha: false, antialias: false, preserveDrawingBuffer: false, desynchronized: true }) as WebGL2RenderingContext | null;
    if (!gl) throw new Error('WebGL2 없음');
    this.gl = gl;

    const program = link(gl, VERT, FRAG);
    gl.useProgram(program);

    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    const pos = gl.getAttribLocation(program, 'pos');
    gl.enableVertexAttribArray(pos);
    gl.vertexAttribPointer(pos, 2, gl.FLOAT, false, 0, 0);

    this.tex = [makeTexture(gl), makeTexture(gl), makeTexture(gl)];
    for (const [i, name] of ['texY', 'texU', 'texV'].entries()) {
      gl.uniform1i(gl.getUniformLocation(program, name), i);
    }
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1); // 평면의 행 정렬은 1 바이트다
  }

  /** [width*height Y][w/2*h/2 U][w/2*h/2 V] 한 장을 그린다. */
  draw(yuv: Uint8Array, width: number, height: number): void {
    const gl = this.gl;
    if (this.w !== width || this.h !== height) {
      this.w = width;
      this.h = height;
      this.canvas.width = width;
      this.canvas.height = height;
      gl.viewport(0, 0, width, height);
    }
    const cw = width >> 1, ch = height >> 1;
    const ySize = width * height, cSize = cw * ch;
    upload(gl, this.tex[0], 0, width, height, yuv.subarray(0, ySize));
    upload(gl, this.tex[1], 1, cw, ch, yuv.subarray(ySize, ySize + cSize));
    upload(gl, this.tex[2], 2, cw, ch, yuv.subarray(ySize + cSize, ySize + 2 * cSize));
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }
}

function upload(gl: WebGL2RenderingContext, tex: WebGLTexture, unit: number, w: number, h: number, data: Uint8Array): void {
  gl.activeTexture(gl.TEXTURE0 + unit);
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, w, h, 0, gl.RED, gl.UNSIGNED_BYTE, data);
}

function makeTexture(gl: WebGL2RenderingContext): WebGLTexture {
  const tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  return tex;
}

function link(gl: WebGL2RenderingContext, vertSrc: string, fragSrc: string): WebGLProgram {
  const program = gl.createProgram();
  for (const [type, src] of [[gl.VERTEX_SHADER, vertSrc], [gl.FRAGMENT_SHADER, fragSrc]] as const) {
    const shader = gl.createShader(type)!;
    gl.shaderSource(shader, src);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      throw new Error('셰이더 컴파일 실패: ' + gl.getShaderInfoLog(shader));
    }
    gl.attachShader(program, shader);
  }
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    throw new Error('셰이더 링크 실패: ' + gl.getProgramInfoLog(program));
  }
  return program;
}
