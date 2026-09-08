package com.seibel.distanthorizons.core.generation.tasks;

/**
 * SUCCESS <br>
 * REQUIRES_SPLITTING <br>
 * 
 * @see DataSourceRetrievalResult
 */
public enum ERetrievalResultState
{
	SUCCESS,
	/** Read-only server query returned no update; this is not evidence of air. */
	NO_UPDATE,
	REQUIRES_SPLITTING,
}
