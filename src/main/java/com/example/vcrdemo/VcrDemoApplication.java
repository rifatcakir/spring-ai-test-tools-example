package com.example.vcrdemo;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Not meant to be run as a deployed service. This project exists to be tested, not
 * launched -- but every test needs an {@code @SpringBootApplication}-annotated class
 * somewhere in the package tree for {@code @SpringBootTest} to find, and having one
 * makes this look like what it's standing in for: an ordinary Spring AI application
 * that happens to depend on spring-ai-test-vcr in its test scope.
 */
@SpringBootApplication
public class VcrDemoApplication {

}
