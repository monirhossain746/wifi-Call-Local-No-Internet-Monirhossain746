package com.android.wificalllocal;

import com.android.wificalllocal.network.NetworkUtils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetworkUtilsTest {

    @Test
    public void testIpv4Validation() {
        assertTrue(NetworkUtils.isValidIpv4("192.168.1.45"));
        assertTrue(NetworkUtils.isValidIpv4("10.0.0.1"));
        assertTrue(NetworkUtils.isValidIpv4("172.16.254.2"));
        assertFalse(NetworkUtils.isValidIpv4("999.168.1.1"));
        assertFalse(NetworkUtils.isValidIpv4("192.168.1"));
        assertFalse(NetworkUtils.isValidIpv4(""));
        assertFalse(NetworkUtils.isValidIpv4(null));
    }

    @Test
    public void testPrivateLanDetection() {
        assertTrue(NetworkUtils.isPrivateLanIp("192.168.43.1"));
        assertTrue(NetworkUtils.isPrivateLanIp("10.12.34.56"));
        assertTrue(NetworkUtils.isPrivateLanIp("172.16.0.1"));
        assertTrue(NetworkUtils.isPrivateLanIp("172.31.255.254"));

        assertFalse(NetworkUtils.isPrivateLanIp("172.15.0.1"));
        assertFalse(NetworkUtils.isPrivateLanIp("172.32.0.1"));
        assertFalse(NetworkUtils.isPrivateLanIp("8.8.8.8"));
    }
}
