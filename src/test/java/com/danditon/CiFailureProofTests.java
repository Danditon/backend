package com.danditon;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

class CiFailureProofTests {

	@Test
	void intentionallyFailsToProveRequiredCiBlocksMerging() {
		fail("intentional Backend CI protection proof");
	}

}
