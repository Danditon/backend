package com.danditon.common.storage;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class UploadsResourceConfiguration implements WebMvcConfigurer {

	private final Path root;

	public UploadsResourceConfiguration(@Value("${danditon.storage.root:/app/uploads}") String root) {
		this.root = Path.of(root).toAbsolutePath().normalize();
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/uploads/quest/**").addResourceLocations(location(FileCategory.QUEST));
		registry.addResourceHandler("/uploads/submission/**").addResourceLocations(location(FileCategory.SUBMISSION));
	}

	private String location(FileCategory category) {
		String uri = root.resolve(category.getDirectory()).toUri().toString();
		return uri.endsWith("/") ? uri : uri + "/";
	}
}
