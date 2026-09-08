package com.seibel.distanthorizons.common.backports;

#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import net.minecraft.entity.EntityLivingBase;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
#endif

/**
 * Recreation of MC's 1.12+ camera object
 * so 1.7.10 can use similar code to 1.12.
 */
public class Camera
{
	#if MC_VER <= MC_1_7_10
	
	public static final Camera INSTANCE = new Camera();
	
    private final Vector3d pos = new Vector3d();

	public void update(EntityLivingBase entity, float partialTicks)
	{
		DhVec3d snapshot = getPositionSnapshot(entity, partialTicks);
		if (snapshot == null) { throw new IllegalStateException("No camera entity or model-view matrix available"); }
		this.pos.set(snapshot.x, snapshot.y, snapshot.z);
	}

	/** One caller owns the returned coordinates; background ticks never mutate the render camera. */
	public static DhVec3d getPositionSnapshot(EntityLivingBase entity, float partialTicks)
	{
		if (entity == null) { return null; }
		DhMat4f modelView = ClientApi.RENDER_STATE.mcModelViewMatrix;
		if (modelView == null) { return null; }
		final Vector4f offset = new Vector4f(); // third person offset
		final Matrix4f inverseModelView = modelView
			.createJomlMatrix()
			.invert();
		inverseModelView.transform(offset);
		
		final double camX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks + offset.x;
		final double camY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks + offset.y;
		final double camZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks + offset.z;
		return new DhVec3d(camX, camY, camZ);
	}
	
	public Vector3d getPos() { return this.pos; }
	
	#endif
}
