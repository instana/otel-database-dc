/*
 * (c) Copyright IBM Corp. 2024
 * (c) Copyright Instana Inc.
 */
package com.instana.dc.host.impl.snmphost;

import com.instana.dc.DcUtil;
import com.instana.dc.SimpleQueryResult;
import com.instana.dc.host.AbstractHostDc;
import com.instana.simpsnmp.SimpSnmp;
import com.instana.simpsnmp.SnmpOption;
import com.instana.simpsnmp.SnmpValue;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ResourceAttributes;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.smi.OID;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.instana.dc.DcUtil.mergeResourceAttributesFromEnv;
import static com.instana.dc.host.HostDcUtil.*;
import static com.instana.dc.host.impl.snmphost.SnmpHostUtil.Oid;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

public class SnmpHostDc extends AbstractHostDc {
    private static final Logger logger = Logger.getLogger(SnmpHostDc.class.getName());
    private final SimpSnmp simpSnmp;
    private final String hostName;
    private final String osType;

    public SnmpHostDc(Map<String, Object> properties, String hostSystem) throws IOException {
        super(properties, hostSystem);
        String snmpHost = (String) properties.getOrDefault(SnmpHostUtil.SNMP_HOST, "udp:127.0.0.1/161");
        SnmpOption option = new SnmpOption();
        option.setCommunity((String) properties.getOrDefault("community", "public"));
        option.setRetries((Integer) properties.getOrDefault("retries", 3));
        option.setTimeout((Integer) properties.getOrDefault("timeout", 450));
        option.setVersion((Integer) properties.getOrDefault("version", SnmpConstants.version2c)); //1
        option.setSecurityLevel((Integer) properties.getOrDefault("securityLevel", SecurityLevel.NOAUTH_NOPRIV)); //1
        option.setAuthPassword((String) properties.get("authPassword"));
        option.setPrivacyPassword((String) properties.get("privacyPassword"));
        simpSnmp = new SimpSnmp(snmpHost, option);

        Map<OID, SnmpValue> result = simpSnmp.queryScalarOids(Oid.HOST_NAME, Oid.OS_TYPE);
        String hostName0 = SnmpValue.getString(result, Oid.HOST_NAME, null);
        String osType0 = parseOsType(SnmpValue.getString(result, Oid.OS_TYPE, null));
        hostName = (String) properties.getOrDefault(SnmpHostUtil.HOST_NAME, hostName0);
        osType = (String) properties.getOrDefault(SnmpHostUtil.OS_TYPE, osType0);
    }

    private String parseOsType(String osType0) {
        if (osType0 == null) {
            return "linux";
        }
        return osType0.split(" ")[0].toLowerCase();
    }


    @Override
    public Resource getResourceAttributes() {
        Resource resource = Resource.getDefault().merge(
                Resource.create(
                        Attributes.of(
                                ResourceAttributes.SERVICE_NAME, getServiceName(),
                                ResourceAttributes.SERVICE_INSTANCE_ID, hostName
                        )));

        resource = resource.merge(
                Resource.create(
                        Attributes.of(ResourceAttributes.HOST_NAME, hostName,
                                ResourceAttributes.OS_TYPE, osType,
                                ResourceAttributes.HOST_ID, hostName,
                                stringKey(DcUtil.INSTANA_PLUGIN), "host"
                        )));

        return mergeResourceAttributesFromEnv(resource);
    }

    private void queryScalarOids() throws IOException {
        Map<OID, SnmpValue> result = simpSnmp.queryScalarOids(
                Oid.CPU_TIME__USER,
                Oid.CPU_TIME__SYSTEM,
                Oid.CPU_TIME__IDLE,
                Oid.CPU_TIME__NICE,
                Oid.CPU_LOAD_1M,
                Oid.CPU_LOAD_5M,
                Oid.CPU_LOAD_15M,
                Oid.MEMORY_USAGE__TOTAL,
                Oid.MEMORY_USAGE__FREE,
                Oid.MEMORY_USAGE__BUFFERED,
                Oid.MEMORY_USAGE__CACHED
        );

        getRawMetric(SYSTEM_CPU_TIME_NAME).setValue(SnmpHostUtil.getCpuTimeResults(result));

        getRawMetric(SYSTEM_MEMORY_USAGE_NAME).setValue(SnmpHostUtil.getMemUsageResults(result));

        if (result.containsKey(Oid.CPU_LOAD_1M)) {
            getRawMetric(SYSTEM_CPU_LOAD1_NAME).setValue(result.get(Oid.CPU_LOAD_1M).toDouble());
        }
        if (result.containsKey(Oid.CPU_LOAD_5M)) {
            getRawMetric(SYSTEM_CPU_LOAD5_NAME).setValue(result.get(Oid.CPU_LOAD_5M).toDouble());
        }
        if (result.containsKey(Oid.CPU_LOAD_15M)) {
            getRawMetric(SYSTEM_CPU_LOAD15_NAME).setValue(result.get(Oid.CPU_LOAD_15M).toDouble());
        }
    }

    private void queryDiskIo() throws IOException {
        List<Map<OID, SnmpValue>> result = simpSnmp.queryColumnOids(
                Oid.DISKDEVICE, Oid.DISK_IO__READ, Oid.DISK_IO__WRITE);
        List<SimpleQueryResult> output = new ArrayList<>();

        for (Map<OID, SnmpValue> result1 : result) {
            String device = SnmpValue.getString(result1, Oid.DISKDEVICE, null);
            if (device != null) {
                long r = SnmpValue.getLong(result1, Oid.DISK_IO__READ, 0L);
                long w = SnmpValue.getLong(result1, Oid.DISK_IO__WRITE, 0L);
                output.add(new SimpleQueryResult(r).setKey(device + ":r")
                        .setAttribute("device", device).setAttribute("direction", "read")
                );
                output.add(new SimpleQueryResult(w).setKey(device + ":w")
                        .setAttribute("device", device).setAttribute("direction", "write")
                );
            }
        }

        getRawMetric(SYSTEM_DISK_IO_NAME).setValue(output);
    }

    private void queryFileSystemUsage() throws IOException {
        List<Map<OID, SnmpValue>> result = simpSnmp.queryColumnOids(
                Oid.FILESYSTEMDEVICE, Oid.FILESYSTEM_USAGE__USED,
                Oid.FILESYSTEM_USAGE__ALL, Oid.FILESYSTEM_USAGE__UNIT);
        List<SimpleQueryResult> output = new ArrayList<>();

        int no = 0;
        for (Map<OID, SnmpValue> result1 : result) {
            String device = SnmpValue.getString(result1, Oid.FILESYSTEMDEVICE, null);
            long unit = SnmpValue.getLong(result1, Oid.FILESYSTEM_USAGE__UNIT, 0L);
            if (device != null && unit != 0) {
                long used = SnmpValue.getLong(result1, Oid.FILESYSTEM_USAGE__USED, 0L);
                long all = SnmpValue.getLong(result1, Oid.FILESYSTEM_USAGE__ALL, 0L);
                long free = all - used;
                output.add(new SimpleQueryResult(used * unit).setKey(device + ":u")
                        .setAttribute("device", "dev" + no).setAttribute("mountpoint", device).setAttribute("state", "used")
                        .setAttribute("mode", "-").setAttribute("type", "xfs")
                );
                output.add(new SimpleQueryResult(free * unit).setKey(device + ":f")
                        .setAttribute("device", "dev" + no).setAttribute("mountpoint", device).setAttribute("state", "free")
                        .setAttribute("mode", "-").setAttribute("type", "xfs")
                );
                no++;
            }
        }

        getRawMetric(SYSTEM_FILESYSTEM_USAGE_NAME).setValue(output);
    }

    private void queryNetworkDropped_Error() throws IOException {
        List<Map<OID, SnmpValue>> result = simpSnmp.queryColumnOids(
                Oid.NETWORKDEVICE,
                Oid.NETWORK_DROPPED_RECEIVE, Oid.NETWORK_DROPPED_TRANSMIT,
                Oid.NETWORK_ERRORS_RECEIVE, Oid.NETWORK_ERRORS_TRANSMIT);
        List<SimpleQueryResult> outputD = new ArrayList<>();
        List<SimpleQueryResult> outputE = new ArrayList<>();

        for (Map<OID, SnmpValue> result1 : result) {
            String device = SnmpValue.getString(result1, Oid.NETWORKDEVICE, null);
            if (device != null) {
                long dr = SnmpValue.getLong(result1, Oid.NETWORK_DROPPED_RECEIVE, 0L);
                long dt = SnmpValue.getLong(result1, Oid.NETWORK_DROPPED_TRANSMIT, 0L);
                long er = SnmpValue.getLong(result1, Oid.NETWORK_ERRORS_RECEIVE, 0L);
                long et = SnmpValue.getLong(result1, Oid.NETWORK_ERRORS_TRANSMIT, 0L);
                outputD.add(new SimpleQueryResult(dr).setKey(device + ":r")
                        .setAttribute("device", device).setAttribute("direction", "receive")
                );
                outputD.add(new SimpleQueryResult(dt).setKey(device + ":t")
                        .setAttribute("device", device).setAttribute("direction", "transmit")
                );
                outputE.add(new SimpleQueryResult(er).setKey(device + ":r")
                        .setAttribute("device", device).setAttribute("direction", "receive")
                );
                outputE.add(new SimpleQueryResult(et).setKey(device + ":t")
                        .setAttribute("device", device).setAttribute("direction", "transmit")
                );
            }
        }

        getRawMetric(SYSTEM_NETWORK_DROPPED_NAME).setValue(outputD);
        getRawMetric(SYSTEM_NETWORK_ERRORS_NAME).setValue(outputE);
    }

    private void queryNetworkIo_Packets() throws IOException {
        List<Map<OID, SnmpValue>> result = simpSnmp.queryColumnOids(
                Oid.NETWORKDEVICE,
                Oid.NETWORK_IO_RECEIVE, Oid.NETWORK_IO_TRANSMIT,
                Oid.NETWORK_PACKAGES_RECEIVE, Oid.NETWORK_PACKAGES_TRANSMIT);
        List<SimpleQueryResult> outputI = new ArrayList<>();
        List<SimpleQueryResult> outputP = new ArrayList<>();

        for (Map<OID, SnmpValue> result1 : result) {
            String device = SnmpValue.getString(result1, Oid.NETWORKDEVICE, null);
            if (device != null) {
                long ir = SnmpValue.getLong(result1, Oid.NETWORK_IO_RECEIVE, 0L);
                long it = SnmpValue.getLong(result1, Oid.NETWORK_IO_TRANSMIT, 0L);
                long pr = SnmpValue.getLong(result1, Oid.NETWORK_PACKAGES_RECEIVE, 0L);
                long pt = SnmpValue.getLong(result1, Oid.NETWORK_PACKAGES_TRANSMIT, 0L);
                outputI.add(new SimpleQueryResult(ir).setKey(device + ":r")
                        .setAttribute("device", device).setAttribute("direction", "receive")
                );
                outputI.add(new SimpleQueryResult(it).setKey(device + ":t")
                        .setAttribute("device", device).setAttribute("direction", "transmit")
                );
                outputP.add(new SimpleQueryResult(pr).setKey(device + ":r")
                        .setAttribute("device", device).setAttribute("direction", "receive")
                );
                outputP.add(new SimpleQueryResult(pt).setKey(device + ":t")
                        .setAttribute("device", device).setAttribute("direction", "transmit")
                );
            }
        }

        getRawMetric(SYSTEM_NETWORK_IO_NAME).setValue(outputI);
        getRawMetric(SYSTEM_NETWORK_PACKETS_NAME).setValue(outputP);
    }

    private static void createTcpConnResult(List<SimpleQueryResult> output, Map<Long, Long> tcpConns, long key, String name) {
        Long num = tcpConns.get(key);
        if (num == null) {
            return;
        }
        output.add(new SimpleQueryResult(num).setKey(name)
                .setAttribute("protocol", "tcp").setAttribute("state", name));
    }

    private void queryTcpConnection() throws IOException {
        List<Map<OID, SnmpValue>> result = simpSnmp.queryColumnOids(Oid.NETWORK_CONNECTION);
        List<SimpleQueryResult> output = new ArrayList<>();
        Map<Long, Long> tcpConns = new HashMap<>();
        for (Map<OID, SnmpValue> result1 : result) {
            result1.forEach((oid, snmpValue) -> {
                tcpConns.merge(snmpValue.toLong(), 1L, Long::sum);
            });
        }

        createTcpConnResult(output, tcpConns, 1, "CLOSE");
        createTcpConnResult(output, tcpConns, 2, "LISTEN");
        createTcpConnResult(output, tcpConns, 3, "SYN_SENT");
        createTcpConnResult(output, tcpConns, 4, "SYN_RECV");
        createTcpConnResult(output, tcpConns, 5, "ESTABLISHED");
        createTcpConnResult(output, tcpConns, 6, "FIN_WAIT_1");
        createTcpConnResult(output, tcpConns, 7, "FIN_WAIT_2");
        createTcpConnResult(output, tcpConns, 8, "CLOSE_WAIT");
        createTcpConnResult(output, tcpConns, 9, "LAST_ACK");
        createTcpConnResult(output, tcpConns, 10, "CLOSING");
        createTcpConnResult(output, tcpConns, 11, "TIME_WAIT");
        createTcpConnResult(output, tcpConns, 12, "DELETE");

        getRawMetric(SYSTEM_NETWORK_CONNECTIONS_NAME).setValue(output);
    }

    @Override
    public void collectData() {
        logger.info("Start to collect metrics");
        try {
            queryScalarOids();
            queryDiskIo();
            queryFileSystemUsage();
            queryNetworkDropped_Error();
            queryNetworkIo_Packets();
            queryTcpConnection();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to collectData", e);
        }
    }
}
