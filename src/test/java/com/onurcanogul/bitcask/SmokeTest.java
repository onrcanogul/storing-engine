package com.onurcanogul.bitcask;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest {

    @Test
    void junitAndJava21Work() {
        assertEquals(21, Runtime.version().feature());
    }
}
