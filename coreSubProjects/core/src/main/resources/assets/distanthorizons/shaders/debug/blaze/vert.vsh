#version 330 core

in vec3 vPosition;
in vec4 vColor;

layout (std140) uniform uniformBlock
{
    mat4 uViewProj;
};

out vec4 fColor;

void main()
{
    gl_Position = uViewProj * vec4(vPosition, 1.0);
    fColor = vColor;
}
