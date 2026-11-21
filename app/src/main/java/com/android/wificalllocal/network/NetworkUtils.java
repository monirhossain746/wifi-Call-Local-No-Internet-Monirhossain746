package com.android.wificalllocal.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.regex.Pattern;

public class NetworkUtils {

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
    );

    public static class LocalNetworkInfo {
        public final String ipAddress;
        public final String routeType; // WIFI, HOTSPOT, WIFI_DIRECT, NONE

        public LocalNetworkInfo(String ipAddress, String routeType) {
            this.ipAddress = ipAddress;
            this.routeType = routeType;
        }

        public boolean isValid() {
            return ipAddress != null && !ipAddress.isEmpty() && !"NONE".equals(routeType);
        }
    }

    public static LocalNetworkInfo detectLocalNetwork(Context context) {
        String wifiIp = null;
        String hotspotIp = null;
        String p2pIp = null;

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface nif : Collections.list(interfaces)) {
                    if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                    String name = nif.getName().toLowerCase();
                    if (isCellularOrTunnel(name)) continue;

                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        if (!(addr instanceof Inet4Address)) continue;
                        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                        String host = addr.getHostAddress();
                        if (host == null || host.startsWith("127.") || host.startsWith("169.254.")) continue;

                        if (name.startsWith("p2p")) {
                            p2pIp = host;
                        } else if (name.contains("ap") || name.contains("softap") || name.contains("swlan")
                                || host.startsWith("192.168.43.") || host.startsWith("192.168.137.")) {
                            hotspotIp = host;
                        } else if (name.startsWith("wlan") || name.startsWith("eth") || isPrivateLanIp(host)) {
                            wifiIp = host;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (wifiIp != null) return new LocalNetworkInfo(wifiIp, "WIFI");
        if (hotspotIp != null) return new LocalNetworkInfo(hotspotIp, "HOTSPOT");
        if (p2pIp != null) return new LocalNetworkInfo(p2pIp, "WIFI_DIRECT");
        return new LocalNetworkInfo("", "NONE");
    }

    private static boolean isCellularOrTunnel(String ifName) {
        return ifName.startsWith("rmnet")
                || ifName.startsWith("ccmni")
                || ifName.startsWith("pdp")
                || ifName.startsWith("wwan")
                || ifName.startsWith("tun")
                || ifName.startsWith("ppp")
                || ifName.startsWith("dummy");
    }

    public static boolean isPrivateLanIp(String ip) {
        if (!isValidIpv4(ip)) return false;
        return ip.startsWith("192.168.")
                || ip.startsWith("10.")
                || is172Private(ip);
    }

    private static boolean is172Private(String ip) {
        if (!ip.startsWith("172.")) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean isValidIpv4(String ip) {
        return ip != null && IPV4_PATTERN.matcher(ip.trim()).matches();
    }

    public static boolean isValidPort(int port) {
        return port >= 1024 && port <= 65535;
    }

    public static class HostPort {
        public final String host;
        public final int port;

        public HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Parses "ip:port" and permits ONLY private RFC 1918 LAN IPv4 addresses on unprivileged ports (1024-65535).
     * Rejects public IPs, DNS hostnames, loopback, 0.0.0.0, and link-local metadata endpoints (169.254.x.x).
     */
    public static HostPort parseLocalLanHostPort(String hostPort) {
        if (hostPort == null) return null;
        String trimmed = hostPort.trim();
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || colon != trimmed.lastIndexOf(':') || colon == trimmed.length() - 1) {
            return null;
        }
        String host = trimmed.substring(0, colon).trim();
        String portStr = trimmed.substring(colon + 1).trim();
        if (!isPrivateLanIp(host)) {
            return null;
        }
        try {
            int port = Integer.parseInt(portStr);
            if (!isValidPort(port)) return null;
            return new HostPort(host, port);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean hasInternetConnection(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasNetworkConnectivity(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }
}
