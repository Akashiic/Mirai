import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Measures only the shared-EBO bind strategy; it does not model Minecraft FPS. */
public final class SharedIboBindProbe {
    static final int W=128,H=64,VBO_COUNT=16,CPU_DRAWS=2048,GPU_DRAWS=256,SAMPLES=12;
    static int vao,ibo,fbo,tex,program;
    static int[] vbos=new int[VBO_COUNT];
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    static int shader(int type,String src){
        int s=GL20.glCreateShader(type);GL20.glShaderSource(s,src);GL20.glCompileShader(s);
        check(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(s,16000));return s;
    }
    static int makeProgram(){
        String vs="#version 330\nin vec2 p; in vec3 c; out vec3 vc; void main(){gl_Position=vec4(p,0,1);vc=c;}";
        String fs="#version 330\nin vec3 vc; out vec4 color; void main(){color=vec4(vc,1);}";
        int v=shader(GL20.GL_VERTEX_SHADER,vs),f=shader(GL20.GL_FRAGMENT_SHADER,fs),p=GL20.glCreateProgram();
        GL20.glAttachShader(p,v);GL20.glAttachShader(p,f);GL20.glBindAttribLocation(p,0,"p");GL20.glBindAttribLocation(p,1,"c");
        GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p,16000));
        GL20.glDeleteShader(v);GL20.glDeleteShader(f);return p;
    }
    static void initGeometry(){
        vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);
        GL20.glEnableVertexAttribArray(0);GL20.glEnableVertexAttribArray(1);
        for(int i=0;i<VBO_COUNT;i++){
            float x0=-1f+2f*i/VBO_COUNT,x1=-1f+2f*(i+1)/VBO_COUNT;
            float r=(i+1f)/VBO_COUNT,g=1f-r,b=(i%5)/4f;
            float[] a={x0,-1,r,g,b, x1,-1,r,g,b, x1,1,r,g,b, x0,1,r,g,b};
            FloatBuffer data=BufferUtils.createFloatBuffer(a.length);data.put(a).flip();
            int id=GL15.glGenBuffers();vbos[i]=id;GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,id);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,data,GL15.GL_STATIC_DRAW);
        }
        IntBuffer idx=BufferUtils.createIntBuffer(6);idx.put(new int[]{0,1,2,2,3,0}).flip();
        ibo=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ibo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,idx,GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,0);GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,0);
    }
    static void initTarget(){
        tex=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,W,H,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,tex,0);
        check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"FBO incomplete");
    }
    static void bindVbo(int id){
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,id);
        GL20.glVertexAttribPointer(0,2,GL11.GL_FLOAT,false,20,0L);
        GL20.glVertexAttribPointer(1,3,GL11.GL_FLOAT,false,20,8L);
    }
    static void drawSequence(boolean shared,int group,int total){
        int d=0;
        while(d<total){
            int n=Math.min(group,total-d);
            for(int j=0;j<n;j++,d++){
                bindVbo(vbos[d%VBO_COUNT]);
                if(!shared || j==0)GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ibo);
                GL11.glDrawElements(GL11.GL_TRIANGLES,6,GL11.GL_UNSIGNED_INT,0L);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,0);
                if(!shared)GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,0);
            }
            if(shared)GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,0);
        }
    }
    static ByteBuffer pixels(boolean shared,int group){
        GL11.glClearColor(0,0,0,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL20.glUseProgram(program);drawSequence(shared,group,VBO_COUNT);
        ByteBuffer b=BufferUtils.createByteBuffer(W*H*4);
        GL11.glReadPixels(0,0,W,H,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);return b;
    }
    static int changedBytes(int group){
        ByteBuffer a=pixels(false,group),b=pixels(true,group);int changed=0;
        for(int i=0;i<a.capacity();i++)if(a.get(i)!=b.get(i))changed++;
        return changed;
    }
    static long cpu(boolean shared,int group){
        GL11.glFinish();GL20.glUseProgram(program);long start=System.nanoTime();
        drawSequence(shared,group,CPU_DRAWS);long elapsed=System.nanoTime()-start;
        GL11.glFinish();return elapsed;
    }
    static long gpu(boolean shared,int group)throws Exception{
        GL11.glFinish();GL20.glUseProgram(program);int a=GL15.glGenQueries(),b=GL15.glGenQueries();
        try{
            GL33.glQueryCounter(a,GL33.GL_TIMESTAMP);drawSequence(shared,group,GPU_DRAWS);GL33.glQueryCounter(b,GL33.GL_TIMESTAMP);
            boolean ready=false;for(int i=0;i<300;i++){Thread.sleep(1);if(GL33.glGetQueryObjectui64(b,GL15.GL_QUERY_RESULT_AVAILABLE)!=0){ready=true;break;}}
            check(ready,"GPU timestamp timeout");return GL33.glGetQueryObjectui64(b,GL15.GL_QUERY_RESULT)-GL33.glGetQueryObjectui64(a,GL15.GL_QUERY_RESULT);
        }finally{GL15.glDeleteQueries(a);GL15.glDeleteQueries(b);}
    }
    static double medianMs(long[] values){
        long[] c=values.clone();Arrays.sort(c);return (c[c.length/2-1]+c[c.length/2])/2.0/1e6;
    }
    static String arr(long[] v){return Arrays.toString(v);}
    public static void main(String[] args)throws Exception{
        Pbuffer pb=null;List<String> cases=new ArrayList<String>();
        try{
            pb=new Pbuffer(16,16,new PixelFormat(),null);pb.makeCurrent();
            check(GLContext.getCapabilities().OpenGL33,"OpenGL 3.3 required");
            System.out.println("GPU="+GL11.glGetString(GL11.GL_RENDERER)+" VERSION="+GL11.glGetString(GL11.GL_VERSION));
            program=makeProgram();initGeometry();initTarget();GL11.glViewport(0,0,W,H);
            GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_DITHER);
            int[] groups={1,2,3,4,8,16};
            for(int group:groups){
                int changed=changedBytes(group);cpu(false,group);cpu(true,group);gpu(false,group);gpu(true,group);
                long[] cb=new long[SAMPLES],cc=new long[SAMPLES],gb=new long[SAMPLES],gc=new long[SAMPLES];
                for(int n=0;n<SAMPLES;n++){
                    if((n&1)==0){cb[n]=cpu(false,group);cc[n]=cpu(true,group);gb[n]=gpu(false,group);gc[n]=gpu(true,group);}
                    else{cc[n]=cpu(true,group);cb[n]=cpu(false,group);gc[n]=gpu(true,group);gb[n]=gpu(false,group);}
                }
                String item="{\"group\":"+group+",\"changed_rgba8_bytes\":"+changed+
                    ",\"cpu_baseline_median_ms\":"+medianMs(cb)+",\"cpu_candidate_median_ms\":"+medianMs(cc)+
                    ",\"gpu_baseline_median_ms\":"+medianMs(gb)+",\"gpu_candidate_median_ms\":"+medianMs(gc)+
                    ",\"cpu_baseline_ns\":"+arr(cb)+",\"cpu_candidate_ns\":"+arr(cc)+
                    ",\"gpu_baseline_ns\":"+arr(gb)+",\"gpu_candidate_ns\":"+arr(gc)+"}";
                cases.add(item);System.out.println("CASE="+item);
            }
            String result="{\"width\":"+W+",\"height\":"+H+",\"vbo_count\":"+VBO_COUNT+
                ",\"cpu_draws\":"+CPU_DRAWS+",\"gpu_draws\":"+GPU_DRAWS+",\"samples\":"+SAMPLES+
                ",\"cases\":["+String.join(",",cases)+"]}";
            System.out.println("RESULT="+result);
        }finally{
            if(pb!=null){
                try{if(program!=0)GL20.glDeleteProgram(program);}catch(Throwable ignored){}
                try{for(int id:vbos)if(id!=0)GL15.glDeleteBuffers(id);}catch(Throwable ignored){}
                try{if(ibo!=0)GL15.glDeleteBuffers(ibo);}catch(Throwable ignored){}
                try{if(fbo!=0)GL30.glDeleteFramebuffers(fbo);}catch(Throwable ignored){}
                try{if(tex!=0)GL11.glDeleteTextures(tex);}catch(Throwable ignored){}
                try{if(vao!=0)GL30.glDeleteVertexArrays(vao);}catch(Throwable ignored){}
                pb.destroy();
            }
        }
    }
}
