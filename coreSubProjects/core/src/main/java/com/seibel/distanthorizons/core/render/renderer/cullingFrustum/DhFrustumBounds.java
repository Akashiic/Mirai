package com.seibel.distanthorizons.core.render.renderer.cullingFrustum;

import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;


public class DhFrustumBounds implements IDhApiCullingFrustum
{
	private final FrustumIntersection frustum;
	// Like the frustum itself, this scratch matrix belongs to this instance and
	// is updated only by the render thread. Never retain the caller's matrix.
	private final Matrix4f worldViewProjection = new Matrix4f();
	public float worldMinY;
	public float worldMaxY;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public DhFrustumBounds()
	{
		this.frustum = new FrustumIntersection();
	}
	
	
	
	//=========//
	// methods //
	//=========//
	
	@Override
	public void update(int worldMinBlockY, int worldMaxBlockY, DhApiMat4f dhWorldViewProjection)
	{
		this.worldMinY = worldMinBlockY;
		this.worldMaxY = worldMaxBlockY;
		
		// Preserve the DH row-major -> JOML column-major conversion. The former
		// inverse/AABB calculation was unused by intersects(), so no inverse or
		// temporary matrices are needed to prepare the same six culling planes.
		this.worldViewProjection.set(
			dhWorldViewProjection.m00, dhWorldViewProjection.m10, dhWorldViewProjection.m20, dhWorldViewProjection.m30,
			dhWorldViewProjection.m01, dhWorldViewProjection.m11, dhWorldViewProjection.m21, dhWorldViewProjection.m31,
			dhWorldViewProjection.m02, dhWorldViewProjection.m12, dhWorldViewProjection.m22, dhWorldViewProjection.m32,
			dhWorldViewProjection.m03, dhWorldViewProjection.m13, dhWorldViewProjection.m23, dhWorldViewProjection.m33);
		this.frustum.set(this.worldViewProjection);
	}
	
	@Override
	public boolean intersects(int lodBlockPosMinX, int lodBlockPosMinZ, int lodBlockWidth, int lodDetailLevel)
	{
		// JOML's vector overload delegates to this scalar overload. Keep exactly
		// the same coordinates and plane boundary semantics without allocating
		// two vectors for every section tested each frame.
		return this.frustum.testAab(
			lodBlockPosMinX, this.worldMinY, lodBlockPosMinZ,
			lodBlockPosMinX + lodBlockWidth, this.worldMaxY, lodBlockPosMinZ + lodBlockWidth);
	}
	
	
	
	//=====================//
	// overridable methods //
	//=====================//
	
	@Override 
	public int getPriority() { return IOverrideInjector.CORE_PRIORITY; }
	
}
