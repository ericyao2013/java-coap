/*
 * Copyright (C) 2011-2017 ARM Limited. All rights reserved.
 */
package org.mbed.coap.transport.udp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.util.concurrent.Executor;
import org.mbed.coap.exception.CoapException;
import org.mbed.coap.packet.CoapPacket;
import org.mbed.coap.transport.CoapReceiver;
import org.mbed.coap.transport.CoapTransport;
import org.mbed.coap.transport.TransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author szymon
 */
public final class MulticastSocketTransport implements CoapTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(MulticastSocketTransport.class.getName());
    private final InetSocketAddress bindSocket;
    private final Executor receivedMessageWorker;
    private MulticastSocket mcastSocket;
    private final InetAddress mcastGroup;
    private Thread readerThread;

    public final static String MCAST_LINKLOCAL_ALLNODES = "FF02::1";    //NOPMD
    public final static String MCAST_NODELOCAL_ALLNODES = "FF01::1";    //NOPMD

    public MulticastSocketTransport(InetSocketAddress bindSocket, String multicastGroup, Executor receivedMessageWorker) throws UnknownHostException {
        this.bindSocket = bindSocket;
        this.receivedMessageWorker = receivedMessageWorker;
        mcastGroup = InetAddress.getByName(multicastGroup);
    }

    @Override
    public void stop() {
        mcastSocket.close();
        readerThread.interrupt();
    }

    @Override
    public void start(CoapReceiver coapReceiver) throws IOException {
        mcastSocket = new MulticastSocket(bindSocket);
        mcastSocket.joinGroup(mcastGroup);
        LOGGER.debug("CoAP server binds on multicast " + mcastSocket.getLocalSocketAddress());

        readerThread = new Thread(() -> readingLoop(coapReceiver), "multicast-reader");
        readerThread.start();
    }

    private void readingLoop(CoapReceiver coapReceiver) {
        byte[] readBuffer = new byte[2048];

        try {
            while (true) {
                DatagramPacket datagramPacket = new DatagramPacket(readBuffer, readBuffer.length);
                mcastSocket.receive(datagramPacket);
                InetSocketAddress adr = (InetSocketAddress) datagramPacket.getSocketAddress();
                if (LOGGER.isDebugEnabled() && adr.getAddress().isMulticastAddress()) {
                    LOGGER.debug("Received multicast message from: " + datagramPacket.getSocketAddress());
                }

                try {
                    final CoapPacket coapPacket = CoapPacket.read(adr, datagramPacket.getData(), datagramPacket.getLength());
                    receivedMessageWorker.execute(() -> coapReceiver.handle(coapPacket, TransportContext.NULL));
                } catch (CoapException e) {
                    LOGGER.warn(e.getMessage());
                }
            }
        } catch (IOException ex) {
            if (!ex.getMessage().startsWith("Socket closed")) {
                LOGGER.warn(ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void sendPacket(CoapPacket coapPacket, InetSocketAddress adr, TransportContext tranContext) throws CoapException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        coapPacket.writeTo(baos);
        byte[] data = baos.toByteArray();
        baos.close();

        DatagramPacket datagramPacket = new DatagramPacket(data, data.length, adr);
        mcastSocket.send(datagramPacket);
    }

    @Override
    public InetSocketAddress getLocalSocketAddress() {
        return (InetSocketAddress) mcastSocket.getLocalSocketAddress();
    }

}
