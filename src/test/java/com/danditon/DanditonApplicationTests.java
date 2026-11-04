package com.danditon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.unit.DataSize;

@SpringBootTest
@ActiveProfiles("test")
class DanditonApplicationTests {

	@Autowired
	private MultipartProperties multipartProperties;

	@Test
	void contextLoads() {
	}

	@Test
	void multipartAllowsTenMegabyteFileWithRequestOverhead() {
		assertEquals(DataSize.ofMegabytes(10), multipartProperties.getMaxFileSize());
		assertEquals(DataSize.ofMegabytes(12), multipartProperties.getMaxRequestSize());
	}

}
