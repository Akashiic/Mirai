import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Native R16F A/B probe for the production SSAO RAW fragment shader. */
public final class RawSsaoProbe {
    static final int W=1280,H=720,BATCH_DRAWS=4;
    static int vao,vbo,fbo,out,depthTex;
    static final List<String> results=new ArrayList<String>();
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    static int shader(int type,String src){
        int s=GL20.glCreateShader(type);GL20.glShaderSource(s,src);GL20.glCompileShader(s);
        check(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(s,16000));return s;
    }
    static int program(Path folder,String frag)throws Exception{
        int vs=shader(GL20.GL_VERTEX_SHADER,new String(Files.readAllBytes(folder.resolve("quad.vert")),"UTF-8"));
        int fs=shader(GL20.GL_FRAGMENT_SHADER,new String(Files.readAllBytes(folder.resolve(frag)),"UTF-8"));
        int p=GL20.glCreateProgram();GL20.glAttachShader(p,vs);GL20.glAttachShader(p,fs);
        GL20.glBindAttribLocation(p,0,"vPosition");GL20.glLinkProgram(p);
        check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p,16000));
        GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);return p;
    }
    static int depthTexture(FloatBuffer data){
        int t=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_DEPTH_COMPONENT32F,W,H,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,data);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_BASE_LEVEL,0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LEVEL,0);return t;
    }
    static void scene(int kind){
        if(depthTex!=0)GL11.glDeleteTextures(depthTex);
        FloatBuffer d=BufferUtils.createFloatBuffer(W*H);Random r=new Random(9017+kind);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            float v;
            if(kind==0)v=0.99f;
            else if(kind==1)v=x<W/2?0.9995f:0.85f;
            else if(kind==2)v=(x<5||y<5||x>=W-5||y>=H-5||x>3*W/4)?1f:0.94f+0.059f*r.nextFloat();
            else {float nx=(x-W/2f)/(W/2f),ny=(y-H/2f)/(H/2f);float q=(float)Math.sqrt(nx*nx+ny*ny);v=q>0.92f?1f:Math.min(0.9998f,0.78f+0.20f*q+0.015f*r.nextFloat());}
            d.put(v);
        }
        d.flip();depthTex=depthTexture(d);
    }
    static FloatBuffer mat(float[] a){FloatBuffer b=BufferUtils.createFloatBuffer(16);b.put(a).flip();return b;}
    static float[][] projection(){
        float near=0.1f,far=2000f,aspect=(float)W/H;
        float f=(float)(1.0/Math.tan(Math.toRadians(70.0)/2.0));
        float c=(far+near)/(near-far),e=(2f*far*near)/(near-far);
        float[] p={f/aspect,0,0,0, 0,f,0,0, 0,0,c,-1, 0,0,e,0};
        float[] inv={aspect/f,0,0,0, 0,1f/f,0,0, 0,0,0,1f/e, 0,0,-1,c/e};
        return new float[][]{p,inv};
    }
    static void uf(int p,String n,float x){GL20.glUniform1f(GL20.glGetUniformLocation(p,n),x);}
    static void ui(int p,String n,int x){GL20.glUniform1i(GL20.glGetUniformLocation(p,n),x);}
    static void setup(int p){
        GL20.glUseProgram(p);GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,depthTex);
        ui(p,"uDepthMap",0);ui(p,"uSampleCount",6);uf(p,"uRadius",4f);uf(p,"uStrength",0.2f);
        uf(p,"uMinLight",0.25f);uf(p,"uBias",0.02f);uf(p,"uFadeDistanceInBlocks",1600f);
        float[][] m=projection();int lp=GL20.glGetUniformLocation(p,"uProj"),li=GL20.glGetUniformLocation(p,"uInvProj");
        GL20.glUniformMatrix4(lp,false,mat(m[0]));GL20.glUniformMatrix4(li,false,mat(m[1]));
    }
    static ByteBuffer pixels(int p){
        setup(p);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);
        ByteBuffer b=BufferUtils.createByteBuffer(W*H*2);GL11.glReadPixels(0,0,W,H,GL11.GL_RED,GL30.GL_HALF_FLOAT,b);
        return b.order(ByteOrder.nativeOrder());
    }
    static long time(int p)throws Exception{
        setup(p);int a=GL15.glGenQueries(),b=GL15.glGenQueries();
        try{
            GL33.glQueryCounter(a,GL33.GL_TIMESTAMP);for(int n=0;n<BATCH_DRAWS;n++)GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);GL33.glQueryCounter(b,GL33.GL_TIMESTAMP);
            boolean ready=false;for(int k=0;k<2000;k++){Thread.sleep(1);if(GL33.glGetQueryObjectui64(b,GL15.GL_QUERY_RESULT_AVAILABLE)!=0){ready=true;break;}}
            check(ready,"GPU timestamp timeout");return (GL33.glGetQueryObjectui64(b,GL15.GL_QUERY_RESULT)-GL33.glGetQueryObjectui64(a,GL15.GL_QUERY_RESULT))/BATCH_DRAWS;
        }finally{GL15.glDeleteQueries(a);GL15.glDeleteQueries(b);}
    }
    static double median(long[] v){long[] c=v.clone();Arrays.sort(c);return (c[c.length/2-1]+c[c.length/2])/2.0/1e6;}
    static String arr(long[] v){return Arrays.toString(v);}
    public static void main(String[] args)throws Exception{
        Path folder=Paths.get(args[0]);String variant=args[1];Pbuffer pb=null;int base=0,cand=0;
        try{
            pb=new Pbuffer(16,16,new PixelFormat(),null);pb.makeCurrent();check(GLContext.getCapabilities().OpenGL33,"GL3.3 required");
            System.out.println("GPU="+GL11.glGetString(GL11.GL_RENDERER)+" VERSION="+GL11.glGetString(GL11.GL_VERSION));
            base=program(folder,"raw-base.frag");cand=program(folder,variant+".frag");
            vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);vbo=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            FloatBuffer verts=BufferUtils.createFloatBuffer(12);verts.put(new float[]{-1,-1,1,-1,1,1,-1,-1,1,1,-1,1}).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,verts,GL15.GL_STATIC_DRAW);GL20.glVertexAttribPointer(0,2,GL11.GL_FLOAT,false,8,0L);GL20.glEnableVertexAttribArray(0);
            out=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,out);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_R16F,W,H,0,GL11.GL_RED,GL30.GL_HALF_FLOAT,(ByteBuffer)null);
            fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,out,0);
            check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"R16F FBO");
            GL11.glViewport(0,0,W,H);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_CULL_FACE);
            for(int kind=0;kind<4;kind++){
                scene(kind);ByteBuffer x=pixels(base),y=pixels(cand);long changed=0;
                for(int n=0;n<x.capacity();n+=2)if(x.getShort(n)!=y.getShort(n))changed++;
                for(int n=0;n<3;n++){time(base);time(cand);}
                long[] before=new long[12],after=new long[12];
                for(int n=0;n<12;n++){if((n&1)==0){before[n]=time(base);after[n]=time(cand);}else{after[n]=time(cand);before[n]=time(base);}}
                String item="{\"scene\":"+kind+",\"changed_r16f_pixels\":"+changed+",\"baseline_median_ms\":"+median(before)+",\"candidate_median_ms\":"+median(after)+",\"baseline_ns\":\""+arr(before)+"\",\"candidate_ns\":\""+arr(after)+"\"}";
                results.add(item);System.out.println("CASE="+item);
            }
            System.out.println("RESULT={\"variant\":\""+variant+"\",\"width\":"+W+",\"height\":"+H+",\"batch_draws\":"+BATCH_DRAWS+",\"cases\":["+String.join(",",results)+"]}");
        }finally{
            if(pb!=null){if(depthTex!=0)GL11.glDeleteTextures(depthTex);if(out!=0)GL11.glDeleteTextures(out);if(fbo!=0)GL30.glDeleteFramebuffers(fbo);if(vbo!=0)GL15.glDeleteBuffers(vbo);if(vao!=0)GL30.glDeleteVertexArrays(vao);if(base!=0)GL20.glDeleteProgram(base);if(cand!=0)GL20.glDeleteProgram(cand);pb.destroy();}
        }
    }
}
