package com.animegirlthralls;

/** Bundled model decoding or validation failed. */
final class ModelResourceException extends Exception
{
	ModelResourceException(String message)
	{
		super(message);
	}

	ModelResourceException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
