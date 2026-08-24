package com.manara.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ManaraBackendApplication {

	static void main(String[] args) {
		SpringApplication.run(ManaraBackendApplication.class, args);
	}

}
