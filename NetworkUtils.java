package com.example.commandemulator;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NetworkUtils - Utilidades avanzadas de red para el Emulador de Comandos.
 * 
 * Características:
 * - Monitorización de cambios de red en tiempo real
 * - Información detallada de interfaces de red
 * - Ping avanzado con estadísticas
 * - Escaneo de puertos básico
 * - Resolución DNS avanzada
 * - Información WiFi detallada (SSID, BSSID, intensidad)
 * - Análisis de red local
 * - Soporte IPv4/IPv6 dual
 * - Cache inteligente de resultados
 * - Callbacks asíncronos para cambios de red
 */
public class NetworkUtils {
    
    private static final String TAG = "NetworkUtils";
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final int PING_COUNT = 4;
    private static final int PING_TIMEOUT_MS = 2000;
    private static final int PORT_SCAN_TIMEOUT_MS = 1000;
    
    // Executor para operaciones de red
    private static final ExecutorService networkExecutor = Executors.newFixedThreadPool(4);
    
    // Cache para resultados
    private static final NetworkCache cache = new NetworkCache();
    
    // Callback para cambios de red
    public interface NetworkChangeCallback {
        void onNetworkConnected(@NonNull NetworkInfo info);
        void onNetworkDisconnected();
        void onNetworkChanged(@NonNull NetworkInfo oldInfo, @NonNull NetworkInfo newInfo);
    }
    
    /**
     * Información detallada de red.
     */
    public static class NetworkInfo {
        private final boolean isConnected;
        private final NetworkType networkType;
        private final String ssid;
        private final String bssid;
        private final int signalStrength; // dBm
        private final List<String> ipAddresses;
        private final List<String> dnsServers;
        private final String gateway;
        private final String subnetMask;
        private final boolean isMetered;
        private final boolean isVPN;
        private final long timestamp;
        
        public enum NetworkType {
            WIFI,
            CELLULAR,
            ETHERNET,
            VPN,
            UNKNOWN,
            NONE
        }
        
        public NetworkInfo(boolean isConnected, NetworkType networkType, String ssid, 
                          String bssid, int signalStrength, List<String> ipAddresses,
                          List<String> dnsServers, String gateway, String subnetMask,
                          boolean isMetered, boolean isVPN) {
            this.isConnected = isConnected;
            this.networkType = networkType;
            this.ssid = ssid;
            this.bssid = bssid;
            this.signalStrength = signalStrength;
            this.ipAddresses = ipAddresses != null ? ipAddresses : new ArrayList<>();
            this.dnsServers = dnsServers != null ? dnsServers : new ArrayList<>();
            this.gateway = gateway;
            this.subnetMask = subnetMask;
            this.isMetered = isMetered;
            this.isVPN = isVPN;
            this.timestamp = System.currentTimeMillis();
        }
        
        // Getters
        public boolean isConnected() { return isConnected; }
        public NetworkType getNetworkType() { return networkType; }
        public String getSsid() { return ssid; }
        public String getBssid() { return bssid; }
        public int getSignalStrength() { return signalStrength; }
        public List<String> getIpAddresses() { return new ArrayList<>(ipAddresses); }
        public List<String> getDnsServers() { return new ArrayList<>(dnsServers); }
        public String getGateway() { return gateway; }
        public String getSubnetMask() { return subnetMask; }
        public boolean isMetered() { return isMetered; }
        public boolean isVPN() { return isVPN; }
        public long getTimestamp() { return timestamp; }
        
        @NonNull
        @Override
        public String toString() {
            return String.format(
                "NetworkInfo{connected=%s, type=%s, ssid=%s, ip=%s, signal=%ddBm}",
                isConnected, networkType, ssid, 
                ipAddresses.isEmpty() ? "N/A" : ipAddresses.get(0),
                signalStrength
            );
        }
        
        @NonNull
        public String toDetailedString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Información de Red ===\n");
            sb.append("Estado: ").append(isConnected ? "CONECTADO" : "DESCONECTADO").append("\n");
            sb.append("Tipo: ").append(networkType).append("\n");
            
            if (ssid != null && !ssid.isEmpty()) {
                sb.append("SSID: ").append(ssid).append("\n");
            }
            if (bssid != null && !bssid.isEmpty()) {
                sb.append("BSSID: ").append(bssid).append("\n");
            }
            
            sb.append("Señal: ").append(signalStrength).append(" dBm\n");
            sb.append("Metered: ").append(isMetered ? "Sí" : "No").append("\n");
            sb.append("VPN: ").append(isVPN ? "Sí" : "No").append("\n");
            
            sb.append("\n=== Direcciones IP ===\n");
            for (String ip : ipAddresses) {
                sb.append("  ").append(ip).append("\n");
            }
            
            if (!dnsServers.isEmpty()) {
                sb.append("\n=== Servidores DNS ===\n");
                for (String dns : dnsServers) {
                    sb.append("  ").append(dns).append("\n");
                }
            }
            
            if (gateway != null && !gateway.isEmpty()) {
                sb.append("Gateway: ").append(gateway).append("\n");
            }
            
            if (subnetMask != null && !subnetMask.isEmpty()) {
                sb.append("Máscara: ").append(subnetMask).append("\n");
            }
            
            sb.append("\nActualizado: ").append(timestamp);
            
            return sb.toString();
        }
    }
    
    /**
     * Resultado de ping con estadísticas.
     */
    public static class PingResult {
        private final String host;
        private final String ipAddress;
        private final boolean reachable;
        private final float avgResponseTime; // ms
        private final int packetsSent;
        private final int packetsReceived;
        private final float packetLoss; // porcentaje
        private final List<Float> responseTimes;
        private final String error;
        
        public PingResult(String host, String ipAddress, boolean reachable, 
                         float avgResponseTime, int packetsSent, int packetsReceived,
                         List<Float> responseTimes, String error) {
            this.host = host;
            this.ipAddress = ipAddress;
            this.reachable = reachable;
            this.avgResponseTime = avgResponseTime;
            this.packetsSent = packetsSent;
            this.packetsReceived = packetsReceived;
            this.packetLoss = packetsSent > 0 ? 
                (packetsSent - packetsReceived) * 100f / packetsSent : 0;
            this.responseTimes = responseTimes != null ? responseTimes : new ArrayList<>();
            this.error = error;
        }
        
        // Getters
        public String getHost() { return host; }
        public String getIpAddress() { return ipAddress; }
        public boolean isReachable() { return reachable; }
        public float getAvgResponseTime() { return avgResponseTime; }
        public int getPacketsSent() { return packetsSent; }
        public int getPacketsReceived() { return packetsReceived; }
        public float getPacketLoss() { return packetLoss; }
        public List<Float> getResponseTimes() { return new ArrayList<>(responseTimes); }
        public String getError() { return error; }
        
        @NonNull
        @Override
        public String toString() {
            if (!reachable && error != null) {
                return String.format("Ping a %s: %s", host, error);
            }
            
            return String.format(
                "Ping a %s [%s]:\n" +
                "  Paquetes: %d enviados, %d recibidos, %.1f%% pérdida\n" +
                "  Tiempo aprox: min=%.1fms, avg=%.1fms, max=%.1fms",
                host, ipAddress,
                packetsSent, packetsReceived, packetLoss,
                getMinResponseTime(), avgResponseTime, getMaxResponseTime()
            );
        }
        
        private float getMinResponseTime() {
            if (responseTimes.isEmpty()) return 0;
            float min = Float.MAX_VALUE;
            for (float time : responseTimes) {
                if (time < min) min = time;
            }
            return min;
        }
        
        private float getMaxResponseTime() {
            if (responseTimes.isEmpty()) return 0;
            float max = 0;
            for (float time : responseTimes) {
                if (time > max) max = time;
            }
            return max;
        }
    }
    
    /**
     * Resultado de escaneo de puertos.
     */
    public static class PortScanResult {
        private final String host;
        private final String ipAddress;
        private final List<Integer> openPorts;
        private final List<Integer> closedPorts;
        private final long scanDuration; // ms
        
        public PortScanResult(String host, String ipAddress, List<Integer> openPorts,
                             List<Integer> closedPorts, long scanDuration) {
            this.host = host;
            this.ipAddress = ipAddress;
            this.openPorts = openPorts != null ? openPorts : new ArrayList<>();
            this.closedPorts = closedPorts != null ? closedPorts : new ArrayList<>();
            this.scanDuration = scanDuration;
        }
        
        // Getters
        public String getHost() { return host; }
        public String getIpAddress() { return ipAddress; }
        public List<Integer> getOpenPorts() { return new ArrayList<>(openPorts); }
        public List<Integer> getClosedPorts() { return new ArrayList<>(closedPorts); }
        public long getScanDuration() { return scanDuration; }
        
        @NonNull
        @Override
        public String toString() {
            return String.format(
                "Escaneo de %s [%s]:\n" +
                "  Puertos abiertos: %s\n" +
                "  Puertos cerrados: %s\n" +
                "  Duración: %d ms",
                host, ipAddress,
                openPorts.toString(),
                closedPorts.size() > 10 ? closedPorts.size() + " puertos" : closedPorts.toString(),
                scanDuration
            );
        }
    }
    
    /**
     * Cache para resultados de red.
     */
    private static class NetworkCache {
        private static final long CACHE_DURATION_MS = 30000; // 30 segundos
        
        private static class CacheEntry {
            final Object value;
            final long timestamp;
            
            CacheEntry(Object value) {
                this.value = value;
                this.timestamp = System.currentTimeMillis();
            }
            
            boolean isValid() {
                return System.currentTimeMillis() - timestamp < CACHE_DURATION_MS;
            }
        }
        
        private final java.util.Map<String, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();
        
        @Nullable
        public <T> T get(@NonNull String key, @NonNull Class<T> type) {
            CacheEntry entry = cache.get(key);
            if (entry != null && entry.isValid()) {
                try {
                    return type.cast(entry.value);
                } catch (ClassCastException e) {
                    // Invalid cache entry, remove it
                    cache.remove(key);
                }
            }
            return null;
        }
        
        public void put(@NonNull String key, @NonNull Object value) {
            cache.put(key, new CacheEntry(value));
        }
        
        public void clear() {
            cache.clear();
        }
        
        public void clearExpired() {
            for (java.util.Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                if (!entry.getValue().isValid()) {
                    cache.remove(entry.getKey());
                }
            }
        }
    }
    
    /**
     * Monitor de cambios de red.
     */
    public static class NetworkMonitor {
        private final ConnectivityManager connectivityManager;
        private final List<NetworkChangeCallback> callbacks = new ArrayList<>();
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private NetworkInfo lastNetworkInfo;
        private boolean isMonitoring = false;
        
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        public NetworkMonitor(@NonNull Context context) {
            this.connectivityManager = (ConnectivityManager) 
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        }
        
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        public void startMonitoring() {
            if (isMonitoring || connectivityManager == null) {
                return;
            }
            
            isMonitoring = true;
            
            NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
            
            ConnectivityManager.NetworkCallback networkCallback = 
                new ConnectivityManager.NetworkCallback() {
                    
                    @Override
                    public void onAvailable(@NonNull Network network) {
                        Log.d(TAG, "Red disponible");
                        NetworkInfo info = getDetailedNetworkInfo();
                        notifyNetworkConnected(info);
                    }
                    
                    @Override
                    public void onLost(@NonNull Network network) {
                        Log.d(TAG, "Red perdida");
                        notifyNetworkDisconnected();
                    }
                    
                    @Override
                    public void onCapabilitiesChanged(@NonNull Network network, 
                                                     @NonNull NetworkCapabilities capabilities) {
                        Log.d(TAG, "Capacidades de red cambiadas");
                        NetworkInfo oldInfo = lastNetworkInfo;
                        NetworkInfo newInfo = getDetailedNetworkInfo();
                        notifyNetworkChanged(oldInfo, newInfo);
                    }
                };
            
            connectivityManager.registerNetworkCallback(request, networkCallback);
            Log.i(TAG, "Monitor de red iniciado");
        }
        
        public void stopMonitoring() {
            isMonitoring = false;
            Log.i(TAG, "Monitor de red detenido");
        }
        
        public void addCallback(@NonNull NetworkChangeCallback callback) {
            if (!callbacks.contains(callback)) {
                callbacks.add(callback);
            }
        }
        
        public void removeCallback(@NonNull NetworkChangeCallback callback) {
            callbacks.remove(callback);
        }
        
        private void notifyNetworkConnected(@NonNull final NetworkInfo info) {
            mainHandler.post(() -> {
                for (NetworkChangeCallback callback : callbacks) {
                    try {
                        callback.onNetworkConnected(info);
                    } catch (Exception e) {
                        Log.e(TAG, "Error en callback onNetworkConnected", e);
                    }
                }
            });
        }
        
        private void notifyNetworkDisconnected() {
            mainHandler.post(() -> {
                for (NetworkChangeCallback callback : callbacks) {
                    try {
                        callback.onNetworkDisconnected();
                    } catch (Exception e) {
                        Log.e(TAG, "Error en callback onNetworkDisconnected", e);
                    }
                }
            });
        }
        
        private void notifyNetworkChanged(@Nullable final NetworkInfo oldInfo, 
                                         @NonNull final NetworkInfo newInfo) {
            if (oldInfo == null) return;
            
            mainHandler.post(() -> {
                for (NetworkChangeCallback callback : callbacks) {
                    try {
                        callback.onNetworkChanged(oldInfo, newInfo);
                    } catch (Exception e) {
                        Log.e(TAG, "Error en callback onNetworkChanged", e);
                    }
                }
            });
        }
    }
    
    // =====================
    // Métodos públicos principales
    // =====================
    
    /**
     * Verifica si hay conexión a Internet con verificación activa.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    public static boolean isNetworkAvailable(@Nullable Context context) {
        if (context == null) {
            Log.w(TAG, "Context es nulo");
            return false;
        }
        
        String cacheKey = "network_available_" + System.currentTimeMillis() / 10000; // Cache por 10s
        Boolean cached = cache.get(cacheKey, Boolean.class);
        if (cached != null) {
            return cached;
        }
        
        ConnectivityManager cm = (ConnectivityManager) 
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager es nulo");
            return false;
        }
        
        boolean isAvailable;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) {
                isAvailable = false;
            } else {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                isAvailable = capabilities != null && 
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                     capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                     capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
        } else {
            NetworkInfo networkInfo = cm.getActiveNetworkInfo();
            isAvailable = networkInfo != null && 
                         networkInfo.isConnected() &&
                         networkInfo.isAvailable();
        }
        
        cache.put(cacheKey, isAvailable);
        return isAvailable;
    }
    
    /**
     * Obtiene información detallada de la red actual.
     */
    @RequiresPermission(allOf = {
        android.Manifest.permission.ACCESS_NETWORK_STATE,
        android.Manifest.permission.ACCESS_WIFI_STATE
    })
    @NonNull
    public static NetworkInfo getDetailedNetworkInfo(@Nullable Context context) {
        if (context == null) {
            return new NetworkInfo(false, NetworkInfo.NetworkType.NONE, 
                null, null, 0, null, null, null, null, false, false);
        }
        
        String cacheKey = "detailed_network_info";
        NetworkInfo cached = cache.get(cacheKey, NetworkInfo.class);
        if (cached != null && cached.isConnected()) {
            // Cache más corto para información de red conectada
            if (System.currentTimeMillis() - cached.getTimestamp() < 10000) {
                return cached;
            }
        }
        
        ConnectivityManager cm = (ConnectivityManager) 
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (cm == null) {
            return new NetworkInfo(false, NetworkInfo.NetworkType.NONE, 
                null, null, 0, null, null, null, null, false, false);
        }
        
        boolean isConnected = false;
        NetworkInfo.NetworkType networkType = NetworkInfo.NetworkType.NONE;
        String ssid = null;
        String bssid = null;
        int signalStrength = 0;
        List<String> ipAddresses = new ArrayList<>();
        List<String> dnsServers = new ArrayList<>();
        String gateway = null;
        String subnetMask = null;
        boolean isMetered = false;
        boolean isVPN = false;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network != null) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                if (capabilities != null) {
                    isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        networkType = NetworkInfo.NetworkType.WIFI;
                        WifiManager wifiManager = (WifiManager) 
                            context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        if (wifiManager != null) {
                            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                            if (wifiInfo != null) {
                                ssid = wifiInfo.getSSID().replace("\"", "");
                                bssid = wifiInfo.getBSSID();
                                signalStrength = wifiInfo.getRssi();
                            }
                        }
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        networkType = NetworkInfo.NetworkType.CELLULAR;
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        networkType = NetworkInfo.NetworkType.ETHERNET;
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        networkType = NetworkInfo.NetworkType.VPN;
                        isVPN = true;
                    }
                    
                    isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                    
                    // Obtener información de red detallada
                    LinkProperties linkProperties = cm.getLinkProperties(network);
                    if (linkProperties != null) {
                        for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                            ipAddresses.add(linkAddress.getAddress().getHostAddress());
                        }
                        
                        for (InetAddress dns : linkProperties.getDnsServers()) {
                            dnsServers.add(dns.getHostAddress());
                        }
                        
                        if (linkProperties.getRoutes().size() > 0) {
                            gateway = linkProperties.getRoutes().iterator().next()
                                .getGateway().getHostAddress();
                        }
                    }
                }
            }
        } else {
            android.net.NetworkInfo legacyInfo = cm.getActiveNetworkInfo();
            if (legacyInfo != null) {
                isConnected = legacyInfo.isConnected();
                switch (legacyInfo.getType()) {
                    case ConnectivityManager.TYPE_WIFI:
                        networkType = NetworkInfo.NetworkType.WIFI;
                        WifiManager wifiManager = (WifiManager) 
                            context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        if (wifiManager != null) {
                            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                            if (wifiInfo != null) {
                                ssid = wifiInfo.getSSID().replace("\"", "");
                                bssid = wifiInfo.getBSSID();
                                signalStrength = wifiInfo.getRssi();
                            }
                        }
                        break;
                    case ConnectivityManager.TYPE_MOBILE:
                        networkType = NetworkInfo.NetworkType.CELLULAR;
                        break;
                    case ConnectivityManager.TYPE_ETHERNET:
                        networkType = NetworkInfo.NetworkType.ETHERNET;
                        break;
                    case ConnectivityManager.TYPE_VPN:
                        networkType = NetworkInfo.NetworkType.VPN;
                        isVPN = true;
                        break;
                }
                
                // Obtener direcciones IP manualmente para versiones anteriores
                try {
                    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                    while (interfaces.hasMoreElements()) {
                        NetworkInterface iface = interfaces.nextElement();
                        if (iface.isUp() && !iface.isLoopback()) {
                            for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                                if (addr.getAddress() instanceof Inet4Address) {
                                    ipAddresses.add(addr.getAddress().getHostAddress());
                                    subnetMask = getSubnetMask(addr.getNetworkPrefixLength());
                                }
                            }
                        }
                    }
                } catch (SocketException e) {
                    Log.e(TAG, "Error obteniendo interfaces de red", e);
                }
            }
        }
        
        NetworkInfo info = new NetworkInfo(isConnected, networkType, ssid, bssid, 
            signalStrength, ipAddresses, dnsServers, gateway, subnetMask, isMetered, isVPN);
        
        cache.put(cacheKey, info);
        return info;
    }
    
    /**
     * Resuelve un host a su dirección IP con soporte IPv4/IPv6.
     */
    @NonNull
    public static Future<DnsResult> resolveHostAsync(@NonNull final String host) {
        return networkExecutor.submit(() -> {
            String cacheKey = "dns_" + host;
            DnsResult cached = cache.get(cacheKey, DnsResult.class);
            if (cached != null) {
                return cached;
            }
            
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                List<String> ipv4 = new ArrayList<>();
                List<String> ipv6 = new ArrayList<>();
                
                for (InetAddress addr : addresses) {
                    if (addr instanceof Inet4Address) {
                        ipv4.add(addr.getHostAddress());
                    } else if (addr instanceof Inet6Address) {
                        ipv6.add(addr.getHostAddress());
                    }
                }
                
                DnsResult result = new DnsResult(host, ipv4, ipv6, null);
                cache.put(cacheKey, result);
                return result;
                
            } catch (UnknownHostException e) {
                DnsResult result = new DnsResult(host, new ArrayList<>(), new ArrayList<>(), 
                    "No se pudo resolver el host: " + e.getMessage());
                return result;
            } catch (Exception e) {
                Log.e(TAG, "Error resolviendo host: " + host, e);
                return new DnsResult(host, new ArrayList<>(), new ArrayList<>(), 
                    "Error: " + e.getMessage());
            }
        });
    }
    
    /**
     * Realiza ping avanzado con estadísticas.
     */
    @NonNull
    public static Future<PingResult> pingAsync(@NonNull final String host, final int count) {
        return networkExecutor.submit(() -> {
            String cacheKey = "ping_" + host + "_" + count;
            PingResult cached = cache.get(cacheKey, PingResult.class);
            if (cached != null) {
                return cached;
            }
            
            List<Float> responseTimes = new ArrayList<>();
            int packetsSent = count;
            int packetsReceived = 0;
            float totalTime = 0;
            String resolvedIp = null;
            String error = null;
            
            try {
                InetAddress address = InetAddress.getByName(host);
                resolvedIp = address.getHostAddress();
                
                for (int i = 0; i < count; i++) {
                    long startTime = System.nanoTime();
                    boolean reachable = address.isReachable(PING_TIMEOUT_MS);
                    long endTime = System.nanoTime();
                    
                    if (reachable) {
                        float responseTime = (endTime - startTime) / 1_000_000f; // ms
                        responseTimes.add(responseTime);
                        totalTime += responseTime;
                        packetsReceived++;
                    }
                    
                    // Pequeña pausa entre pings
                    if (i < count - 1) {
                        Thread.sleep(500);
                    }
                }
                
            } catch (UnknownHostException e) {
                error = "Host desconocido: " + e.getMessage();
            } catch (IOException e) {
                error = "Error de red: " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "Ping interrumpido";
            } catch (Exception e) {
                error = "Error inesperado: " + e.getMessage();
            }
            
            float avgResponseTime = packetsReceived > 0 ? totalTime / packetsReceived : 0;
            boolean reachable = packetsReceived > 0;
            
            PingResult result = new PingResult(host, resolvedIp, reachable, 
                avgResponseTime, packetsSent, packetsReceived, responseTimes, error);
            
            if (reachable) {
                cache.put(cacheKey, result);
            }
            
            return result;
        });
    }
    
    /**
     * Escanea puertos en un host específico.
     */
    @NonNull
    public static Future<PortScanResult> scanPortsAsync(@NonNull final String host, 
                                                       @NonNull final List<Integer> ports) {
        return networkExecutor.submit(() -> {
            String cacheKey = "portscan_" + host + "_" + ports.hashCode();
            PortScanResult cached = cache.get(cacheKey, PortScanResult.class);
            if (cached != null) {
                return cached;
            }
            
            long startTime = System.currentTimeMillis();
            List<Integer> openPorts = new ArrayList<>();
            List<Integer> closedPorts = new ArrayList<>();
            String resolvedIp = null;
            
            try {
                InetAddress address = InetAddress.getByName(host);
                resolvedIp = address.getHostAddress();
                
                for (int port : ports) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new java.net.InetSocketAddress(address, port), 
                            PORT_SCAN_TIMEOUT_MS);
                        openPorts.add(port);
                    } catch (IOException e) {
                        closedPorts.add(port);
                    }
                }
                
            } catch (UnknownHostException e) {
                Log.e(TAG, "Host desconocido: " + host, e);
            } catch (Exception e) {
                Log.e(TAG, "Error escaneando puertos: " + host, e);
            }
            
            long scanDuration = System.currentTimeMillis() - startTime;
            PortScanResult result = new PortScanResult(host, resolvedIp, 
                openPorts, closedPorts, scanDuration);
            
            cache.put(cacheKey, result);
            return result;
        });
    }
    
    /**
     * Obtiene todas las interfaces de red del dispositivo.
     */
    @NonNull
    public static List<NetworkInterfaceInfo> getNetworkInterfaces() {
        List<NetworkInterfaceInfo> interfaces = new ArrayList<>();
        
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface iface = networkInterfaces.nextElement();
                
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                
                String name = iface.getName();
                String displayName = iface.getDisplayName();
                byte[] macAddress = iface.getHardwareAddress();
                String macStr = macAddress != null ? formatMacAddress(macAddress) : "N/A";
                int mtu = iface.getMTU();
                boolean isVirtual = iface.isVirtual();
                
                List<String> ipAddresses = new ArrayList<>();
                List<String> ipv6Addresses = new ArrayList<>();
                
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    String ip = addr.getAddress().getHostAddress();
                    if (addr.getAddress() instanceof Inet4Address) {
                        ipAddresses.add(ip);
                    } else if (addr.getAddress() instanceof Inet6Address) {
                        ipv6Addresses.add(ip);
                    }
                }
                
                interfaces.add(new NetworkInterfaceInfo(name, displayName, macStr, 
                    ipAddresses, ipv6Addresses, mtu, isVirtual));
            }
            
        } catch (SocketException e) {
            Log.e(TAG, "Error obteniendo interfaces de red", e);
        }
        
        return interfaces;
    }
    
    // =====================
    // Clases de datos auxiliares
    // =====================
    
    public static class DnsResult {
        private final String host;
        private final List<String> ipv4Addresses;
        private final List<String> ipv6Addresses;
        private final String error;
        
        public DnsResult(String host, List<String> ipv4Addresses, 
                        List<String> ipv6Addresses, String error) {
            this.host = host;
            this.ipv4Addresses = ipv4Addresses != null ? ipv4Addresses : new ArrayList<>();
            this.ipv6Addresses = ipv6Addresses != null ? ipv6Addresses : new ArrayList<>();
            this.error = error;
        }
        
        // Getters
        public String getHost() { return host; }
        public List<String> getIpv4Addresses() { return new ArrayList<>(ipv4Addresses); }
        public List<String> getIpv6Addresses() { return new ArrayList<>(ipv6Addresses); }
        public String getError() { return error; }
        
        @NonNull
        @Override
        public String toString() {
            if (error != null) {
                return String.format("Resolución de %s: %s", host, error);
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("Resolución de ").append(host).append(":\n");
            
            if (!ipv4Addresses.isEmpty()) {
                sb.append("  IPv4:\n");
                for (String ip : ipv4Addresses) {
                    sb.append("    ").append(ip).append("\n");
                }
            }
            
            if (!ipv6Addresses.isEmpty()) {
                sb.append("  IPv6:\n");
                for (String ip : ipv6Addresses) {
                    sb.append("    ").append(ip).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
    
    public static class NetworkInterfaceInfo {
        private final String name;
        private final String displayName;
        private final String macAddress;
        private final List<String> ipv4Addresses;
        private final List<String> ipv6Addresses;
        private final int mtu;
        private final boolean isVirtual;
        
        public NetworkInterfaceInfo(String name, String displayName, String macAddress,
                                   List<String> ipv4Addresses, List<String> ipv6Addresses,
                                   int mtu, boolean isVirtual) {
            this.name = name;
            this.displayName = displayName;
            this.macAddress = macAddress;
            this.ipv4Addresses = ipv4Addresses != null ? ipv4Addresses : new ArrayList<>();
            this.ipv6Addresses = ipv6Addresses != null ? ipv6Addresses : new ArrayList<>();
            this.mtu = mtu;
            this.isVirtual = isVirtual;
        }
        
        // Getters
        public String getName() { return name; }
        public String getDisplayName() { return displayName; }
        public String getMacAddress() { return macAddress; }
        public List<String> getIpv4Addresses() { return new ArrayList<>(ipv4Addresses); }
        public List<String> getIpv6Addresses() { return new ArrayList<>(ipv6Addresses); }
        public int getMtu() { return mtu; }
        public boolean isVirtual() { return isVirtual; }
        
        @NonNull
        @Override
        public String toString() {
            return String.format("%s (%s) - MAC: %s, IPv4: %s, MTU: %d", 
                name, displayName, macAddress, 
                ipv4Addresses.isEmpty() ? "N/A" : ipv4Addresses.get(0), 
                mtu);
        }
    }
    
    // =====================
    // Métodos de utilidad
    // =====================
    
    private static String formatMacAddress(byte[] mac) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? ":" : ""));
        }
        return sb.toString();
    }
    
    private static String getSubnetMask(int prefixLength) {
        int mask = 0xffffffff << (32 - prefixLength);
        return String.format("%d.%d.%d.%d",
            (mask >> 24) & 0xff,
            (mask >> 16) & 0xff,
            (mask >> 8) & 0xff,
            mask & 0xff);
    }
    
    /**
     * Limpia la cache de red.
     */
    public static void clearCache() {
        cache.clear();
        Log.d(TAG, "Cache de red limpiado");
    }
    
    /**
     * Apaga el executor de red.
     */
    public static void shutdown() {
        networkExecutor.shutdown();
        try {
            if (!networkExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                networkExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            networkExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        Log.d(TAG, "NetworkUtils shutdown completo");
    }
}
