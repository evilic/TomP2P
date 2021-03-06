package net.tomp2p.relay;

import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import net.tomp2p.dht.FutureGet;
import net.tomp2p.dht.FuturePut;
import net.tomp2p.dht.PeerBuilderDHT;
import net.tomp2p.dht.PeerDHT;
import net.tomp2p.futures.FutureBootstrap;
import net.tomp2p.futures.FutureDirect;
import net.tomp2p.nat.FutureRelayNAT;
import net.tomp2p.nat.PeerBuilderNAT;
import net.tomp2p.nat.PeerNAT;
import net.tomp2p.p2p.Peer;
import net.tomp2p.p2p.PeerBuilder;
import net.tomp2p.p2p.RequestP2PConfiguration;
import net.tomp2p.p2p.RoutingConfiguration;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number320;
import net.tomp2p.peers.Number640;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerMap;
import net.tomp2p.peers.PeerMapConfiguration;
import net.tomp2p.peers.PeerSocketAddress;
import net.tomp2p.relay.buffer.MessageBufferConfiguration;
import net.tomp2p.relay.tcp.TCPRelayClientConfig;
import net.tomp2p.relay.tcp.TCPRelayServerConfig;
import net.tomp2p.relay.tcp.buffered.BufferedTCPRelayClientConfig;
import net.tomp2p.relay.tcp.buffered.BufferedTCPRelayServerConfig;
import net.tomp2p.rpc.DispatchHandler;
import net.tomp2p.rpc.ObjectDataReply;
import net.tomp2p.storage.Data;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestRelay {

	private static final Random rnd = new Random(42);
	private final RelayType relayType;
	private final RelayServerConfig serverConfig;
	private final RelayClientConfig clientConfig;

	@SuppressWarnings("rawtypes")
	@Parameterized.Parameters
	public static Collection data() throws Exception {
		MessageBufferConfiguration ageLimit = new MessageBufferConfiguration().bufferAgeLimit(2000).bufferCountLimit(Integer.MAX_VALUE).bufferSizeLimit(Long.MAX_VALUE);
		MessageBufferConfiguration singleBuf = new MessageBufferConfiguration().bufferAgeLimit(Long.MAX_VALUE).bufferCountLimit(1).bufferSizeLimit(Long.MAX_VALUE);
		
		return Arrays.asList(new Object[][] {
				{ RelayType.OPENTCP, new TCPRelayServerConfig(), new TCPRelayClientConfig().peerMapUpdateInterval(5) },
				{ RelayType.BUFFERED_OPENTCP, new BufferedTCPRelayServerConfig(ageLimit), new BufferedTCPRelayClientConfig().peerMapUpdateInterval(5) },
				{ RelayType.BUFFERED_OPENTCP, new BufferedTCPRelayServerConfig(singleBuf), new BufferedTCPRelayClientConfig().peerMapUpdateInterval(5) } });
	}

	public TestRelay(RelayType relayType, RelayServerConfig serverConfig, RelayClientConfig clientConfig) {
		this.relayType = relayType;
		this.serverConfig = serverConfig;
		this.clientConfig = clientConfig;
	}

	private void waitMapUpdate() throws InterruptedException {
		Thread.sleep(clientConfig.peerMapUpdateInterval() * 1000);
	}

	@Test
	public void testSetupRelayPeers() throws Exception {
		final int nrOfNodes = 200;
		Peer master = null;
		Peer unreachablePeer = null;
		try {
			// setup test peers
			Peer[] peers = UtilsNAT.createNodes(nrOfNodes, rnd, 4001);
			master = peers[0];
			UtilsNAT.perfectRouting(peers);
			for (Peer peer : peers) {
				new PeerBuilderNAT(peer).addRelayServerConfiguration(relayType, serverConfig).start();
			}

			// Test setting up relay peers
			unreachablePeer = new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(5000).start();

			// find neighbors
			FutureBootstrap futureBootstrap = unreachablePeer.bootstrap().peerAddress(peers[0].peerAddress()).start();
			futureBootstrap.awaitUninterruptibly();
			Assert.assertTrue(futureBootstrap.isSuccess());

			// setup relay
			PeerNAT uNat = new PeerBuilderNAT(unreachablePeer).start();
			FutureRelayNAT startRelay = uNat.startRelay(clientConfig, peers[0].peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(startRelay.isSuccess());

			// Check if flags are set correctly
			Assert.assertTrue(unreachablePeer.peerAddress().isRelayed());
			Assert.assertFalse(unreachablePeer.peerAddress().isFirewalledTCP());
			Assert.assertFalse(unreachablePeer.peerAddress().isFirewalledUDP());
		} finally {
			if (master != null) {
				master.shutdown().await();
			}
			if (unreachablePeer != null) {
				unreachablePeer.shutdown().await();
			}
		}
	}

	@Test
	public void testBoostrap() throws Exception {
		final int nrOfNodes = 10;
		Peer master = null;
		Peer unreachablePeer = null;
		try {
			// setup test peers
			Peer[] peers = UtilsNAT.createNodes(nrOfNodes, rnd, 4001);
			master = peers[0];
			UtilsNAT.perfectRouting(peers);
			for (Peer peer : peers) {
				new PeerBuilderNAT(peer).addRelayServerConfiguration(relayType, serverConfig).start();
			}

			// Test setting up relay peers
			unreachablePeer = new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(5000).start();
			PeerAddress upa = unreachablePeer.peerBean().serverPeerAddress();
			upa = upa.changeFirewalledTCP(true).changeFirewalledUDP(true);
			unreachablePeer.peerBean().serverPeerAddress(upa);

			// find neighbors
			FutureBootstrap futureBootstrap = unreachablePeer.bootstrap().peerAddress(peers[0].peerAddress()).start();
			futureBootstrap.awaitUninterruptibly();
			Assert.assertTrue(futureBootstrap.isSuccess());

			// setup relay
			PeerNAT uNat = new PeerBuilderNAT(unreachablePeer).start();
			FutureRelayNAT startRelay = uNat.startRelay(clientConfig, peers[0].peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(startRelay.isSuccess());

			// find neighbors again
			futureBootstrap = unreachablePeer.bootstrap().peerAddress(peers[0].peerAddress()).start();
			futureBootstrap.awaitUninterruptibly();
			Assert.assertTrue(futureBootstrap.isSuccess());

			boolean otherPeersHaveRelay = false;

			for (Peer peer : peers) {
				if (peer.peerBean().peerMap().allOverflow().contains(unreachablePeer.peerAddress())) {
					for (PeerAddress pa : peer.peerBean().peerMap().allOverflow()) {
						if (pa.peerId().equals(unreachablePeer.peerID())) {
							if (pa.peerSocketAddresses().size() > 0) {
								otherPeersHaveRelay = true;
							}
							System.err.println("-->" + pa.peerSocketAddresses());
							System.err.println("relay=" + pa.isRelayed());
						}
					}
					System.err.println("check 1! " + peer.peerAddress());
				}

			}
			Assert.assertTrue(otherPeersHaveRelay);

			// wait for maintenance
			waitMapUpdate();

			boolean otherPeersMe = false;
			for (Peer peer : peers) {
				if (peer.peerBean().peerMap().all().contains(unreachablePeer.peerAddress())) {
					System.err.println("check 2! " + peer.peerAddress());
					otherPeersMe = true;
				}
			}
			Assert.assertTrue(otherPeersMe);
		} finally {
			if (master != null) {
				master.shutdown().await();
			}
			if (unreachablePeer != null) {
				unreachablePeer.shutdown().await();
			}
		}
	}

	/**
	 * Tests sending a message from an unreachable peer to another unreachable peer
	 */
	@Test
	public void testRelaySendDirect() throws Exception {
		final int nrOfNodes = 100;
		Peer master = null;
		Peer unreachablePeer1 = null;
		Peer unreachablePeer2 = null;
		try {
			// setup test peers
			Peer[] peers = UtilsNAT.createNodes(nrOfNodes, rnd, 4001);
			master = peers[0];
			UtilsNAT.perfectRouting(peers);
			for (Peer peer : peers) {
				new PeerBuilderNAT(peer).addRelayServerConfiguration(relayType, serverConfig).start();
			}

			// Test setting up relay peers
			unreachablePeer1 = new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(13337).start();
			PeerNAT uNat1 = new PeerBuilderNAT(unreachablePeer1).start();
			FutureRelayNAT fbn = uNat1.startRelay(clientConfig, master.peerAddress());
			fbn.awaitUninterruptibly();
			Assert.assertTrue(fbn.isSuccess());

			System.out.print("Send direct message to unreachable peer " + unreachablePeer1.peerAddress());
			final String request = "Hello ";
			final String response = "World!";

			final AtomicBoolean test1 = new AtomicBoolean(false);
			final AtomicBoolean test2 = new AtomicBoolean(false);

			// final Peer unr = unreachablePeer;
			unreachablePeer1.objectDataReply(new ObjectDataReply() {
				public Object reply(PeerAddress sender, Object obj) throws Exception {
					test1.set(obj.equals(request));
					Assert.assertEquals(request.toString(), request);
					test2.set(sender.inetAddress().toString().contains("0.0.0.0"));
					System.err.println("Got sender:" + sender);

					// this is too late here, so we cannot test this here
					// Collection<PeerSocketAddress> list = new ArrayList<PeerSocketAddress>();
					// list.add(new PeerSocketAddress(InetAddress.getByName("101.101.101.101"), 101, 101));
					// unr.peerBean().serverPeerAddress(unr.peerBean().serverPeerAddress().changePeerSocketAddresses(list));

					return response;
				}
			});

			unreachablePeer2 = new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(13338).start();
			PeerNAT uNat2 = new PeerBuilderNAT(unreachablePeer2).start();
			fbn = uNat2.startRelay(clientConfig, peers[42].peerAddress());

			fbn.awaitUninterruptibly();
			Assert.assertTrue(fbn.isSuccess());

			// prevent rcon
			Collection<PeerSocketAddress> list = unreachablePeer2.peerBean().serverPeerAddress().peerSocketAddresses();
			if (list.size() >= clientConfig.type().maxRelayCount()) {
				Iterator<PeerSocketAddress> iterator = list.iterator();
				iterator.next();
				iterator.remove();
			}
			list.add(new PeerSocketAddress(InetAddress.getByName("10.10.10.10"), 10, 10));
			unreachablePeer2.peerBean().serverPeerAddress(
					unreachablePeer2.peerBean().serverPeerAddress().changePeerSocketAddresses(list));

			System.err.println("unreachablePeer1: " + unreachablePeer1.peerAddress());
			System.err.println("unreachablePeer2: " + unreachablePeer2.peerAddress());

			FutureDirect fd = unreachablePeer2.sendDirect(unreachablePeer1.peerAddress()).object(request).start()
					.awaitUninterruptibly();
			System.err.println("got msg from: " + fd.responseMessage().sender());
			Assert.assertEquals(response, fd.object());
			// make sure we did not receive it from the unreachable peer with port 13337
			// System.err.println(fd.getWrappedFuture());
			// TODO: this case is true for relay
			// Assert.assertEquals(fd.wrappedFuture().responseMessage().senderSocket().getPort(), 4001);
			// TODO: this case is true for rcon
			Assert.assertEquals(unreachablePeer1.peerID(), fd.responseMessage().sender().peerId());

			Assert.assertTrue(test1.get());
			Assert.assertFalse(test2.get());
			Assert.assertEquals(clientConfig.type().maxRelayCount(), fd.responseMessage().sender()
					.peerSocketAddresses().size());
		} finally {
			if (unreachablePeer1 != null) {
				unreachablePeer1.shutdown().await();
			}
			if (unreachablePeer2 != null) {
				unreachablePeer2.shutdown().await();
			}
			if (master != null) {
				master.shutdown().await();
			}
		}
	}

	/**
	 * Tests sending a message from a reachable peer to an unreachable peer
	 */
	@Test
	public void testRelaySendDirect2() throws Exception {
		final int nrOfNodes = 100;
		Peer master = null;
		Peer unreachablePeer = null;
		try {
			// setup test peers
			Peer[] peers = UtilsNAT.createNodes(nrOfNodes, rnd, 4001);
			master = peers[0];
			UtilsNAT.perfectRouting(peers);
			for (Peer peer : peers) {
				new PeerBuilderNAT(peer).addRelayServerConfiguration(relayType, serverConfig).start();
			}

			// setup relay
			unreachablePeer = new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(13337).start();
			PeerNAT uNat = new PeerBuilderNAT(unreachablePeer).start();
			FutureRelayNAT startRelay = uNat.startRelay(clientConfig, peers[0].peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(startRelay.isSuccess());

			System.out.print("Send direct message to unreachable peer");
			final String request = "Hello ";
			final String response = "World!";

			unreachablePeer.objectDataReply(new ObjectDataReply() {
				public Object reply(PeerAddress sender, Object request) throws Exception {
					Assert.assertEquals(request.toString(), request);
					return response;
				}
			});

			FutureDirect fd = peers[42].sendDirect(unreachablePeer.peerAddress()).object(request).start()
					.awaitUninterruptibly();
			Assert.assertTrue(fd.isSuccess());
			Assert.assertEquals(response, fd.object());

			// make sure we did receive it from the unreachable peer with id
			Assert.assertEquals(unreachablePeer.peerID(), fd.responseMessage().sender().peerId());
		} finally {
			if (unreachablePeer != null) {
				unreachablePeer.shutdown().await();
			}
			if (master != null) {
				master.shutdown().await();
			}
		}
	}

	/**
	 * Tests sending a message from an unreachable peer to a reachable peer
	 */
	@Test
	public void testRelaySendDirect3() throws Exception {
		final int nrOfNodes = 100;
		Peer master = null;
		Peer unreachablePeer = null;
		try {
			// setup test peers
			Peer[] peers = UtilsNAT.createNodes(nrOfNodes, rnd, 4001);
			master = peers[0];
			UtilsNAT.perfectRouting(peers);
			for (Peer peer : peers) {
				new PeerBuilderNAT(peer).addRelayServerConfiguration(relayType, serverConfig).start();
			}

			// setup relay
			unreachablePeer = new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(13337).start();
			PeerNAT uNat = new PeerBuilderNAT(unreachablePeer).start();
			FutureRelayNAT startRelay = uNat.startRelay(clientConfig, peers[0].peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(startRelay.isSuccess());

			System.out.print("Send direct message from unreachable peer");
			final String request = "Hello ";
			final String response = "World!";

			Peer receiver = peers[42];
			receiver.objectDataReply(new ObjectDataReply() {
				public Object reply(PeerAddress sender, Object request) throws Exception {
					Assert.assertEquals(request.toString(), request);
					return response;
				}
			});

			FutureDirect fd = unreachablePeer.sendDirect(receiver.peerAddress()).object(request).start()
					.awaitUninterruptibly();
			Assert.assertEquals(response, fd.object());

			// make sure we did receive it from the unreachable peer with id
			Assert.assertEquals(receiver.peerID(), fd.responseMessage().sender().peerId());
		} finally {
			if (unreachablePeer != null) {
				unreachablePeer.shutdown().await();
			}
			if (master != null) {
				master.shutdown().await();
			}
		}
	}

	@Test
	public void testRelayRouting() throws Exception {
		final int nrOfNodes = 8; // test only works if total nr of nodes is < 8
		Peer master = null;
		Peer unreachablePeer = null;
		try {
			// setup test peers
			Peer[] peers = UtilsNAT.createNodes(nrOfNodes, rnd, 4001);
			master = peers[0];
			UtilsNAT.perfectRouting(peers);
			for (Peer peer : peers) {
				new PeerBuilderNAT(peer).addRelayServerConfiguration(relayType, serverConfig).start();
			}

			// Test setting up relay peers
			unreachablePeer = new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(13337).start();
			PeerAddress upa = unreachablePeer.peerBean().serverPeerAddress();
			upa = upa.changeFirewalledTCP(true).changeFirewalledUDP(true);
			unreachablePeer.peerBean().serverPeerAddress(upa);

			// find neighbors
			FutureBootstrap futureBootstrap = unreachablePeer.bootstrap().peerAddress(peers[0].peerAddress()).start();
			futureBootstrap.awaitUninterruptibly();
			Assert.assertTrue(futureBootstrap.isSuccess());

			// setup relay and lower the update interval to 5s
			PeerNAT uNat = new PeerBuilderNAT(unreachablePeer).start();
			FutureRelayNAT startRelay = uNat.startRelay(clientConfig, peers[0].peerAddress());
			FutureRelay frNAT = startRelay.awaitUninterruptibly().futureRelay();
			Assert.assertTrue(startRelay.isSuccess());

			PeerAddress relayPeer = frNAT.relays().iterator().next().relayAddress();
			Peer found = null;
			for (Peer p : peers) {
				if (p.peerAddress().equals(relayPeer)) {
					found = p;
					break;
				}
			}
			Assert.assertNotNull(found);

			// wait for at least one map update task (5s)
			waitMapUpdate();

			int nrOfNeighbors = getNeighbors(found).size();
			// we have in total 9 peers, we should find 8 as neighbors
			Assert.assertEquals(8, nrOfNeighbors);

			System.err.println("neighbors: " + nrOfNeighbors);
			for (BaseRelayClient relay : frNAT.relays()) {
				System.err.println("pc:" + relay.relayAddress());
			}

			Assert.assertEquals(clientConfig.type().maxRelayCount(), frNAT.relays().size());

			// Shut down a peer
			peers[nrOfNodes - 1].shutdown().await();
			peers[nrOfNodes - 2].shutdown().await();
			peers[nrOfNodes - 3].shutdown().await();

			/*
			 * needed because failure of a node is detected with periodic
			 * heartbeat and the routing table of the relay peers are also
			 * updated periodically
			 */
			waitMapUpdate();

			Assert.assertEquals(nrOfNeighbors - 3, getNeighbors(found).size());
			Assert.assertEquals(clientConfig.type().maxRelayCount(), frNAT.relays().size());
		} finally {
			if (unreachablePeer != null) {
				unreachablePeer.shutdown().await();
			}
			if (master != null) {
				master.shutdown().await();
			}
		}
	}

	@Test
	public void testNoRelayDHT() throws Exception {
		PeerDHT master = null;
		PeerDHT slave = null;
		try {
			PeerDHT[] peers = UtilsNAT.createNodesDHT(10, rnd, 4000);
			master = peers[0]; // the relay peer
			UtilsNAT.perfectRouting(peers);
			for (PeerDHT peer : peers) {
				new PeerBuilderNAT(peer.peer()).addRelayServerConfiguration(relayType, serverConfig).start();
			}
			PeerMapConfiguration pmc = new PeerMapConfiguration(Number160.createHash(rnd.nextInt()));
			slave = new PeerBuilderDHT(new PeerBuilder(Number160.ONE).peerMap(new PeerMap(pmc)).ports(13337).start())
					.start();
			printMapStatus(slave, peers);
			FuturePut futurePut = peers[8].put(slave.peerID()).data(new Data("hello")).start().awaitUninterruptibly();
			futurePut.futureRequests().awaitUninterruptibly();
			Assert.assertTrue(futurePut.isSuccess());
			Assert.assertFalse(slave.storageLayer().contains(
					new Number640(slave.peerID(), Number160.ZERO, Number160.ZERO, Number160.ZERO)));
			System.err.println("DONE!");
		} finally {
			if (master != null) {
				master.shutdown().await();
			}
			if (slave != null) {
				slave.shutdown().await();
			}
		}
	}

	private void printMapStatus(PeerDHT slave, PeerDHT[] peers) {
		for (PeerDHT peer : peers) {
			if (peer.peerBean().peerMap().allOverflow().contains(slave.peerAddress())) {
				System.err.println("found relayed peer in overflow bag " + peer.peerAddress());
			}
		}

		for (PeerDHT peer : peers) {
			if (peer.peerBean().peerMap().all().contains(slave.peerAddress())) {
				System.err.println("found relayed peer in regular bag " + peer.peerAddress());
			}
		}
	}

	@Test
	public void testRelayDHTSimple() throws Exception {
		PeerDHT master = null;
		PeerDHT unreachablePeer = null;
		try {
			PeerDHT[] peers = UtilsNAT.createNodesDHT(1, rnd, 4000);
			master = peers[0]; // the relay peer
			new PeerBuilderNAT(master.peer()).addRelayServerConfiguration(relayType, serverConfig).start();

			// Test setting up relay peers
			unreachablePeer = new PeerBuilderDHT(new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(13337).start())
					.start();
			PeerNAT uNat = new PeerBuilderNAT(unreachablePeer.peer()).start();
			uNat.startRelay(clientConfig, master.peerAddress());

			FutureRelayNAT fbn = uNat.startRelay(clientConfig, master.peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(fbn.isSuccess());

			System.err.println("DONE!");

		} finally {
			if (master != null) {
				master.shutdown().await();
			}
			if (unreachablePeer != null) {
				unreachablePeer.shutdown().await();
			}
		}
	}

	@Test
	public void testRelayDHT() throws Exception {
		PeerDHT master = null;
		PeerDHT unreachablePeer = null;
		try {
			PeerDHT[] peers = UtilsNAT.createNodesDHT(10, rnd, 4000);
			master = peers[0]; // the relay peer
			UtilsNAT.perfectRouting(peers);
			for (PeerDHT peer : peers) {
				new PeerBuilderNAT(peer.peer()).addRelayServerConfiguration(relayType, serverConfig).start();
			}

			// Test setting up relay peers
			unreachablePeer = new PeerBuilderDHT(new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(13337).start())
					.start();
			PeerNAT uNat = new PeerBuilderNAT(unreachablePeer.peer()).start();

			FutureRelayNAT fbn = uNat.startRelay(clientConfig, master.peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(fbn.isSuccess());

			// wait for maintenance to kick in
			waitMapUpdate();

			printMapStatus(unreachablePeer, peers);

			FuturePut futurePut = peers[8].put(unreachablePeer.peerID()).data(new Data("hello")).start()
					.awaitUninterruptibly();
			// the relayed one is the slowest, so we need to wait for it!
			futurePut.futureRequests().awaitUninterruptibly();
			System.err.println(futurePut.failedReason());

			Assert.assertTrue(futurePut.isSuccess());
			// we cannot see the peer in futurePut.rawResult, as the relayed is the slowest and we finish
			// earlier than that.
			Assert.assertTrue(unreachablePeer.storageLayer().contains(
					new Number640(unreachablePeer.peerID(), Number160.ZERO, Number160.ZERO, Number160.ZERO)));
			System.err.println("DONE!");

		} finally {
			if (master != null) {
				master.shutdown().await();
			}
			if (unreachablePeer != null) {
				unreachablePeer.shutdown().await();
			}
		}
	}

	@Test
	public void testRelayDHTPutGet() throws Exception {
		PeerDHT master = null;
		PeerDHT unreachablePeer = null;
		try {
			PeerDHT[] peers = UtilsNAT.createNodesDHT(10, rnd, 4000);
			master = peers[0]; // the relay peer
			UtilsNAT.perfectRouting(peers);
			for (PeerDHT peer : peers) {
				new PeerBuilderNAT(peer.peer()).addRelayServerConfiguration(relayType, serverConfig).start();
			}

			// Test setting up relay peers
			unreachablePeer = new PeerBuilderDHT(new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(13337).start())
					.start();
			PeerNAT uNat = new PeerBuilderNAT(unreachablePeer.peer()).start();

			// bootstrap
			unreachablePeer.peer().bootstrap().peerAddress(master.peerAddress()).start();
			FutureRelayNAT fbn = uNat.startRelay(clientConfig, master.peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(fbn.isSuccess());

			// wait for maintenance to kick in
			waitMapUpdate();

			printMapStatus(unreachablePeer, peers);

			RoutingConfiguration r = new RoutingConfiguration(5, 1, 1);
			RequestP2PConfiguration rp = new RequestP2PConfiguration(1, 1, 0);

			System.err.println("Unreachable: " + unreachablePeer.peerID());
			System.err.println("Relay: " + master.peerID());

			FuturePut futurePut = peers[8].put(unreachablePeer.peerID()).data(new Data("hello")).routingConfiguration(r)
					.requestP2PConfiguration(rp).start().awaitUninterruptibly();
			// the relayed one is the slowest, so we need to wait for it!
			futurePut.futureRequests().awaitUninterruptibly();
			System.err.println(futurePut.failedReason());

			Assert.assertTrue(futurePut.isSuccess());
			Assert.assertTrue(unreachablePeer.storageLayer().contains(
					new Number640(unreachablePeer.peerID(), Number160.ZERO, Number160.ZERO, Number160.ZERO)));

			FutureGet futureGet = peers[8].get(unreachablePeer.peerID()).routingConfiguration(r).requestP2PConfiguration(rp)
					.start().awaitUninterruptibly();
			Assert.assertTrue(futureGet.isSuccess());

			System.err.println("DONE!");
		} finally {
			if (master != null) {
				master.shutdown().await();
			}
			if (unreachablePeer != null) {
				unreachablePeer.shutdown().await();
			}
		}
	}

	@Test
	public void testRelayDHTPutGet2() throws Exception {
		PeerDHT master = null;
		PeerDHT unreachablePeer1 = null;
		PeerDHT unreachablePeer2 = null;
		try {
			PeerDHT[] peers = UtilsNAT.createNodesDHT(10, rnd, 4000);
			master = peers[0]; // the relay peer

			for (PeerDHT peer : peers) {
				new PeerBuilderNAT(peer.peer()).addRelayServerConfiguration(relayType, serverConfig).start();
			}

			// Test setting up relay peers
			unreachablePeer1 = new PeerBuilderDHT(new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(13337).start())
					.start();
			PeerNAT uNat1 = new PeerBuilderNAT(unreachablePeer1.peer()).start();
			FutureRelayNAT fbn1 = uNat1.startRelay(clientConfig, master.peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(fbn1.isSuccess());

			unreachablePeer2 = new PeerBuilderDHT(new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(13338).start())
					.start();
			PeerNAT uNat2 = new PeerBuilderNAT(unreachablePeer2.peer()).start();
			FutureRelayNAT fbn2 = uNat2.startRelay(clientConfig, master.peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(fbn2.isSuccess());

			peers[8] = unreachablePeer1;
			peers[9] = unreachablePeer2;
			UtilsNAT.perfectRouting(peers);

			// wait for relay setup
			Thread.sleep(5000);

			// wait for maintenance to kick in
			waitMapUpdate();

			printMapStatus(unreachablePeer1, peers);
			printMapStatus(unreachablePeer2, peers);

			RoutingConfiguration r = new RoutingConfiguration(5, 1, 1);
			RequestP2PConfiguration rp = new RequestP2PConfiguration(1, 1, 0);

			System.err.println(unreachablePeer1.peerID()); // f1
			System.err.println(unreachablePeer2.peerID()); // e7

			FuturePut futurePut = unreachablePeer1.put(unreachablePeer2.peerID()).data(new Data("hello"))
					.routingConfiguration(r).requestP2PConfiguration(rp).start().awaitUninterruptibly();
			// the relayed one is the slowest, so we need to wait for it!
			futurePut.futureRequests().awaitUninterruptibly();
			System.err.println(futurePut.failedReason());

			Assert.assertTrue(futurePut.isSuccess());
			Assert.assertTrue(unreachablePeer2.storageLayer().contains(
					new Number640(unreachablePeer2.peerID(), Number160.ZERO, Number160.ZERO, Number160.ZERO)));

			FutureGet futureGet = unreachablePeer1.get(unreachablePeer2.peerID()).routingConfiguration(r)
					.requestP2PConfiguration(rp).fastGet(false).start().awaitUninterruptibly();
			// TODO: try peers even if no data found with fastget
			System.err.println(futureGet.failedReason());
			Assert.assertTrue(futureGet.isSuccess());

			// we cannot see the peer in futurePut.rawResult, as the relayed is the slowest and we finish
			// earlier than that.

			System.err.println("DONE!");

		} finally {
			if (master != null) {
				master.shutdown().await();
			}
			if (unreachablePeer1 != null) {
				unreachablePeer1.shutdown().await();
			}
			if (unreachablePeer2 != null) {
				unreachablePeer2.shutdown().await();
			}
		}
	}

	@Test
	public void testRelayDHTPutGetSigned() throws Exception {
		PeerDHT master = null;
		PeerDHT unreachablePeer1 = null;
		PeerDHT unreachablePeer2 = null;
		try {
			PeerDHT[] peers = UtilsNAT.createNodesDHT(10, rnd, 4000);
			master = peers[0]; // the relay peer

			for (PeerDHT peer : peers) {
				new PeerBuilderNAT(peer.peer()).addRelayServerConfiguration(relayType, serverConfig).start();
			}

			KeyPairGenerator gen = KeyPairGenerator.getInstance("DSA");
			KeyPair pair1 = gen.generateKeyPair();
			KeyPair pair2 = gen.generateKeyPair();

			// Test setting up relay peers
			unreachablePeer1 = new PeerBuilderDHT(new PeerBuilder(Number160.createHash(rnd.nextInt())).keyPair(pair1)
					.ports(13337).start()).start();
			PeerNAT uNat1 = new PeerBuilderNAT(unreachablePeer1.peer()).start();
			FutureRelayNAT fbn1 = uNat1.startRelay(clientConfig, master.peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(fbn1.isSuccess());

			unreachablePeer2 = new PeerBuilderDHT(new PeerBuilder(Number160.createHash(rnd.nextInt())).keyPair(pair2)
					.ports(13338).start()).start();
			PeerNAT uNat2 = new PeerBuilderNAT(unreachablePeer2.peer()).start();
			FutureRelayNAT fbn2 = uNat2.startRelay(clientConfig, master.peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(fbn2.isSuccess());

			peers[8] = unreachablePeer1;
			peers[9] = unreachablePeer2;
			UtilsNAT.perfectRouting(peers);

			// wait for relay setup
			Thread.sleep(5000);

			// wait for maintenance to kick in
			waitMapUpdate();

			printMapStatus(unreachablePeer1, peers);
			printMapStatus(unreachablePeer2, peers);

			RoutingConfiguration r = new RoutingConfiguration(5, 1, 1);
			RequestP2PConfiguration rp = new RequestP2PConfiguration(1, 1, 0);

			System.err.println(unreachablePeer1.peerID()); // ..8bd
			System.err.println(unreachablePeer2.peerID()); // ..af3

			FuturePut futurePut = unreachablePeer1.put(unreachablePeer2.peerID()).data(new Data("hello")).sign()
					.routingConfiguration(r).requestP2PConfiguration(rp).start().awaitUninterruptibly();
			// the relayed one is the slowest, so we need to wait for it!
			futurePut.futureRequests().awaitUninterruptibly();
			System.err.println(futurePut.failedReason());

			Assert.assertTrue(futurePut.isSuccess());
			Assert.assertTrue(unreachablePeer2.storageLayer().contains(
					new Number640(unreachablePeer2.peerID(), Number160.ZERO, Number160.ZERO, Number160.ZERO)));

			FutureGet futureGet = unreachablePeer1.get(unreachablePeer2.peerID()).routingConfiguration(r).sign()
					.requestP2PConfiguration(rp).fastGet(false).start().awaitUninterruptibly();
			// TODO: try peers even if no data found with fastget
			System.err.println(futureGet.failedReason());
			Assert.assertTrue(futureGet.isSuccess());

			// we cannot see the peer in futurePut.rawResult, as the relayed is the slowest and we finish
			// earlier than that.
			System.err.println("DONE!");
		} finally {
			if (master != null) {
				master.shutdown().await();
			}
			if (unreachablePeer1 != null) {
				unreachablePeer1.shutdown().await();
			}
			if (unreachablePeer2 != null) {
				unreachablePeer2.shutdown().await();
			}
		}
	}

	@Test
	public void testVeryFewPeers() throws Exception {
		Peer master = null;
		Peer unreachablePeer = null;
		try {
			Peer[] peers = UtilsNAT.createNodes(3, rnd, 4000);
			master = peers[0]; // the relay peer
			UtilsNAT.perfectRouting(peers);
			for (Peer peer : peers) {
				new PeerBuilderNAT(peer).addRelayServerConfiguration(relayType, serverConfig).start();
			}

			// Test setting up relay peers
			unreachablePeer = new PeerBuilder(Number160.createHash(rnd.nextInt())).ports(13337).start();
			PeerNAT uNat = new PeerBuilderNAT(unreachablePeer).start();
			FutureRelayNAT fbn = uNat.startRelay(clientConfig, master.peerAddress()).awaitUninterruptibly();
			Assert.assertTrue(fbn.isSuccess());
		} finally {
			if (master != null) {
				master.shutdown().await();
			}
			if (unreachablePeer != null) {
				unreachablePeer.shutdown().await();
			}
		}
	}

	private Collection<PeerAddress> getNeighbors(Peer peer) {
		if (peer == null) {
			return Collections.emptyList();
		}

		Map<Number320, DispatchHandler> handlers = peer.connectionBean().dispatcher().searchHandler(5);
		for (Map.Entry<Number320, DispatchHandler> entry : handlers.entrySet()) {
			if (entry.getValue() instanceof BaseRelayServer) {
				return ((BaseRelayServer) entry.getValue()).getPeerMap();
			}
		}
		return Collections.emptyList();
	}

}
