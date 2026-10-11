package com.animegirlthralls;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The custom models a thrall can be replaced with. Each entry names a creators-kit format JSON
 * resource in this package, or none to leave the game's own thrall model alone.
 */
@Getter
@RequiredArgsConstructor
enum AnimeGirl
{
	ORIGINAL(null),
	ESDEATH("esdeath.json");

	private final String resource;
}
