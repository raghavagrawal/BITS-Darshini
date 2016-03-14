/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.bits.protocolanalyzer.analyzer.network;

import java.util.Arrays;

import org.pcap4j.packet.Packet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.bits.protocolanalyzer.analyzer.PacketWrapper;
import com.bits.protocolanalyzer.analyzer.Protocol;
import com.bits.protocolanalyzer.analyzer.event.PacketTypeDetectionEvent;
import com.bits.protocolanalyzer.persistence.entity.IPv4Entity;
import com.bits.protocolanalyzer.persistence.repository.IPv4Repository;
import com.bits.protocolanalyzer.utils.BitOperator;
import com.bits.protocolanalyzer.utils.ByteOperator;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

/**
 *
 * @author crygnus
 */
@Component
public class IPv4Analyzer {

    public static final String PACKET_TYPE_OF_RELEVANCE = Protocol.IPV4;

    @Autowired
    private IPv4Repository ipPv4Repository;

    private EventBus eventBus;

    private byte[] ipv4Header;
    private int headerLength;
    private int startByte;
    private int endByte;

    public void configure(EventBus eventBus) {
        this.eventBus = eventBus;
        this.eventBus.register(this);
    }

    /* Field Extractor methods start */
    public int getIhl(byte[] ipHeader) {
        return BitOperator.getValue(ipHeader[0], IPv4Header.IHL_START_BIT,
                IPv4Header.IHL_BITS);
    }

    public int getTotalLength(byte[] ipHeader) {
        byte[] totalLength = Arrays.copyOfRange(ipHeader,
                IPv4Header.TOTAL_LENGTH_START_BYTE,
                IPv4Header.TOTAL_LENGTH_END_BYTE + 1);
        return ByteOperator.parseBytes(totalLength);
    }

    public int getIdentification(byte[] ipHeader) {
        byte[] identification = Arrays.copyOfRange(ipHeader,
                IPv4Header.IDENTIFICATION_START_BYTE,
                IPv4Header.IDENTIFICATION_END_BYTE + 1);
        return ByteOperator.parseBytes(identification);
    }

    public int getDontFragmentBit(byte[] ipHeader) {
        byte byteWithFlags = ipHeader[6];
        int[] byteWithFlagsBits = BitOperator.getBits(byteWithFlags);
        return byteWithFlagsBits[1];
    }

    public int getMoreFragmentBit(byte[] ipHeader) {
        byte byteWithFlags = ipHeader[6];
        int[] byteWithFlagsBits = BitOperator.getBits(byteWithFlags);
        return byteWithFlagsBits[2];
    }

    public int getFragmentOffset(byte[] ipHeader) {
        byte[] bytesWithFragmentOffset = new byte[2];
        bytesWithFragmentOffset[0] = ipHeader[6];
        bytesWithFragmentOffset[1] = ipHeader[7];
        int fragmentOffset = ByteOperator.parseBytes(bytesWithFragmentOffset);
        /* To neglect 3 flag bits */
        return fragmentOffset & 0x1FFF;
    }

    public int getTTL(byte[] ipHeader) {
        byte[] ttlByte = new byte[1];
        ttlByte[0] = ipHeader[IPv4Header.TTL_BYTE];
        return ByteOperator.parseBytes(ttlByte);
    }

    public int getProtocol(byte[] ipHeader) {
        byte[] protocolByte = new byte[1];
        protocolByte[0] = ipHeader[IPv4Header.PROTOCOL_BYTE];
        int protocolInt = ByteOperator.parseBytes(protocolByte);
        return protocolInt;
    }

    public int getHeaderChecksum(byte[] ipHeader) {
        byte[] headerChecksumBytes = Arrays.copyOfRange(ipHeader,
                IPv4Header.HEADER_CHECKSUM_START_BYTE,
                IPv4Header.HEADER_CHECKSUM_END_BYTE + 1);
        return ByteOperator.parseBytes(headerChecksumBytes);
    }

    public String getSouceAddress(byte[] ipHeader) {

        byte[] sourceAddressBytes = Arrays.copyOfRange(ipHeader,
                IPv4Header.SOURCE_IP_START_BYTE,
                IPv4Header.SOURCE_IP_END_BYTE + 1);

        IPv4Address sourceAddress = new IPv4Address(sourceAddressBytes);
        return sourceAddress.toString();
    }

    public String getDestinationAddress(byte[] ipHeader) {

        byte[] destinationAddressBytes = Arrays.copyOfRange(ipHeader,
                IPv4Header.DESTINATION_IP_START_BYTE,
                IPv4Header.DESTINATION_IP_END_BYTE + 1);

        IPv4Address destAddress = new IPv4Address(destinationAddressBytes);
        return destAddress.toString();
    }
    /* Field extractor methods - End */

    private void setIpv4Header(PacketWrapper packetWrapper) {
        Packet packet = packetWrapper.getPacket();
        byte[] rawPacket = packet.getRawData();
        int startByte = packetWrapper.getStartByte();
        this.ipv4Header = Arrays.copyOfRange(rawPacket, startByte,
                startByte + IPv4Header.DEFAULT_HEADER_LENTH_IN_BYTES + 1);
    }

    private void setStartByte(PacketWrapper packetWrapper) {
        int ihl = getIhl(this.ipv4Header);
        this.headerLength = IPv4Header.DEFAULT_HEADER_LENTH_IN_BYTES;
        this.startByte = packetWrapper.getStartByte() + headerLength;
    }

    public void setEndByte(PacketWrapper packetWrapper) {
        int totalPacketLength = getTotalLength(this.ipv4Header);
        this.endByte = packetWrapper.getStartByte() + totalPacketLength;
    }

    public int getHeaderLength() {
        return this.headerLength;
    }

    @Subscribe
    public void analyzePacket(PacketWrapper packetWrapper) {
        if (PACKET_TYPE_OF_RELEVANCE
                .equalsIgnoreCase(packetWrapper.getPacketType())) {

            /*
             * Do type detection first and publish the event.
             */
            this.setIpv4Header(packetWrapper);
            this.setStartByte(packetWrapper);
            this.setEndByte(packetWrapper);
            String nextPacketType = getNextProtocol(ipv4Header);
            publishTypeDetectionEvent(nextPacketType, this.startByte,
                    this.endByte);

            /*
             * Persist the packet attributes to ipv4_analysis table
             */
            IPv4Entity entity = new IPv4Entity();

            entity.setPacketIdEntity(packetWrapper.getPacketIdEntity());
            entity.setVersion(IPv4Header.IP_VERSION);
            entity.setIhl(getIhl(ipv4Header));
            entity.setTotalLength(getTotalLength(ipv4Header));
            entity.setIdentification(getIdentification(ipv4Header));
            if (getDontFragmentBit(ipv4Header) == 0) {
                entity.setDontFragment(false);
            } else {
                entity.setDontFragment(true);
            }
            if (getMoreFragmentBit(ipv4Header) == 0) {
                entity.setMoreFragment(false);
            } else {
                entity.setMoreFragment(true);
            }
            entity.setTtl(getTTL(ipv4Header));
            entity.setNextProtocol(nextPacketType);
            entity.setChecksum(getHeaderChecksum(ipv4Header));
            entity.setSourceAddr(this.getSouceAddress(ipv4Header));
            entity.setDestinationAddr(this.getDestinationAddress(ipv4Header));

            ipPv4Repository.save(entity);
        }
    }

    private String getNextProtocol(byte[] ipv4Header) {
        int protocolInt = getProtocol(ipv4Header);

        switch (protocolInt) {
        case 6:
            return Protocol.TCP;
        case 17:
            return Protocol.UDP;

        default:
            return Protocol.END_PROTOCOL;
        }
    }

    private void publishTypeDetectionEvent(String nextPacketType, int startByte,
            int endByte) {
        this.eventBus.post(new PacketTypeDetectionEvent(nextPacketType,
                startByte, endByte));
    }

}