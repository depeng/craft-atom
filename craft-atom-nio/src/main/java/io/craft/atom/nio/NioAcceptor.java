package io.craft.atom.nio;

import io.craft.atom.io.IoAcceptor;
import io.craft.atom.io.IoAcceptorX;
import io.craft.atom.io.IoHandler;
import io.craft.atom.io.IoReactorX;
import io.craft.atom.nio.api.NioAcceptorConfig;
import io.craft.atom.nio.spi.NioBufferSizePredictorFactory;
import io.craft.atom.nio.spi.NioChannelEventDispatcher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.ToString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts incoming connection based TCP or datagram based UDP, communicates with clients, and fires events.
 * 
 * @author mindwind
 * @version 1.0, Feb 21, 2013
 * @see NioTcpAcceptor
 * @see NioUdpAcceptor
 */
@ToString(callSuper = true, of = { "config", "bindAddresses", "unbindAddresses", "boundmap" })
abstract public class NioAcceptor extends NioReactor implements IoAcceptor {
	
	
	private static final Logger LOG = LoggerFactory.getLogger(NioAcceptor.class);
	
	
	protected final    Set<SocketAddress>                    bindAddresses   = new HashSet<SocketAddress>()                             ;
	protected final    Set<SocketAddress>                    unbindAddresses = new HashSet<SocketAddress>()                             ;
	protected final    Map<SocketAddress, SelectableChannel> boundmap        = new ConcurrentHashMap<SocketAddress, SelectableChannel>();
	protected final    Object                                lock            = new Object()                                             ;
	protected volatile boolean                               selectable      = false                                                    ;
	protected volatile boolean                               endFlag         = false                                                    ;
	protected          NioAcceptorConfig                     config                                                                     ;
	protected          IOException                           exception                                                                  ;
	protected          Selector                              selector                                                                   ;
	
	
	// ~ ----------------------------------------------------------------------------------------------------------
	
	
	/**
	 * Constructs a new nio acceptor with default configuration, binds to the specified local address port.
	 * 
	 * @param handler
	 * @param port
	 */
	public NioAcceptor(IoHandler handler, int port) {
		this(handler, new NioAcceptorConfig(), port);
	}
	
	/**
	 * Constructs a new nio acceptor with specified configuration, binds to the specified local address port.
	 * 
	 * @param handler
	 * @param config
	 * @param port
	 */
	public NioAcceptor(IoHandler handler, NioAcceptorConfig config, int port) {
		this(handler, config, new InetSocketAddress(port));
	}
	
	/**
	 * Constructs a new nio acceptor with specified configuration and dispatcher, binds to the specified local address port.
	 * 
	 * @param handler
	 * @param config
	 * @param dispatcher
	 * @param port
	 */
	public NioAcceptor(IoHandler handler, NioAcceptorConfig config, NioChannelEventDispatcher dispatcher, int port) {
		this(handler, config, dispatcher, new InetSocketAddress(port));
	}
	
	/**
	 * Constructs a new nio acceptor with specified configuration, dispatcher and predictor factory, binds to the specified local address port.
	 * 
	 * @param handler
	 * @param config
	 * @param dispatcher
	 * @param predictorFactory
	 * @param port
	 */
	public NioAcceptor(IoHandler handler, NioAcceptorConfig config, NioChannelEventDispatcher dispatcher, NioBufferSizePredictorFactory predictorFactory, int port) {
		this(handler, config, dispatcher, predictorFactory, new InetSocketAddress(port));
	}
	
	/**
	 * Constructs a new nio acceptor with default configuration, and binds the specified socket addresses.
	 * 
	 * @param handler
	 * @param firstLocalAddress
	 * @param otherLocalAddresses
	 */
	public NioAcceptor(IoHandler handler, SocketAddress firstLocalAddress, SocketAddress... otherLocalAddresses) {
		this(handler, new NioAcceptorConfig(), firstLocalAddress, otherLocalAddresses);
	}
	
	/**
	 * Constructs a new acceptor the specified configuration, and binds the specified socket addresses.
	 * 
	 * @param handler
	 * @param config
	 * @param firstLocalAddress
	 * @param otherLocalAddresses
	 */
	public NioAcceptor(IoHandler handler, NioAcceptorConfig config, SocketAddress firstLocalAddress, SocketAddress... otherLocalAddresses) {
		this(handler, config, new NioOrderedDirectChannelEventDispatcher(config.getTotalEventSize()), new NioAdaptiveBufferSizePredictorFactory(), firstLocalAddress, otherLocalAddresses);
	}
	
	/**
	 * Constructs a new acceptor the specified configuration and dispatcher, binds the specified socket addresses.
	 * 
	 * @param handler
	 * @param config
	 * @param dispatcher
	 * @param firstLocalAddress
	 * @param otherLocalAddresses
	 */
	public NioAcceptor(IoHandler handler, NioAcceptorConfig config, NioChannelEventDispatcher dispatcher, SocketAddress firstLocalAddress, SocketAddress... otherLocalAddresses) {
		this(handler, config, dispatcher, new NioAdaptiveBufferSizePredictorFactory(), firstLocalAddress, otherLocalAddresses);
	}
	
	/**
	 * Constructs a new acceptor the specified configuration, dispatcher and predictor factory, binds the specified socket addresses.
	 * 
	 * @param handler
	 * @param config
	 * @param dispatcher
	 * @param predictorFactory
	 * @param firstLocalAddress
	 * @param otherLocalAddresses
	 */
	public NioAcceptor(IoHandler handler, NioAcceptorConfig config, NioChannelEventDispatcher dispatcher, NioBufferSizePredictorFactory predictorFactory, SocketAddress firstLocalAddress, SocketAddress... otherLocalAddresses) {
		this(handler, config, dispatcher, predictorFactory);
		
		try {
			bind(firstLocalAddress, otherLocalAddresses);
		} catch (IOException e) {
			throw new RuntimeException("Failed to construct", e);
		} finally {
			if (!selectable && selector != null) {
				try {
					selector.close();
				} catch (IOException e) {
					LOG.warn("[CRAFT-ATOM-NIO] Selector close exception", e);
				}
			}
		}
	}
	
	/**
	 * Constructs a new nio acceptor with default configuration, but not binds to any address.
	 * 
	 * @param handler
	 */
	public NioAcceptor(IoHandler handler) {
		this(handler, new NioAcceptorConfig(), new NioOrderedDirectChannelEventDispatcher(), new NioAdaptiveBufferSizePredictorFactory());
	}
	
	/**
	 * Constructs a new nio acceptor with specified configuration,  but not binds to any address.
	 * 
	 * @param handler
	 * @param config
	 */
	public NioAcceptor(IoHandler handler, NioAcceptorConfig config) {
		this(handler, config, new NioOrderedDirectChannelEventDispatcher(config.getTotalEventSize()), new NioAdaptiveBufferSizePredictorFactory());
	}
	
	/**
	 * Constructs a new nio acceptor with specified configuration and dispatcher, but not binds to any address.
	 * 
	 * @param handler
	 * @param config
	 * @param dispatcher
	 */
	public NioAcceptor(IoHandler handler, NioAcceptorConfig config, NioChannelEventDispatcher dispatcher) {
		this(handler, config, dispatcher, new NioAdaptiveBufferSizePredictorFactory());
	}
	
	/**
	 * Constructs a new acceptor the specified configuration, dispatcher and predictor, but not binds to any address.
	 * 
	 * @param handler
	 * @param config
	 * @param dispatcher
	 * @param predictorFactory
	 */
	public NioAcceptor(IoHandler handler, NioAcceptorConfig config, NioChannelEventDispatcher dispatcher, NioBufferSizePredictorFactory predictorFactory) {
		if (handler == null) {
			throw new IllegalArgumentException("Handler should not be null!");
		}
		
		this.handler = handler;
		this.config = (config == null ? new NioAcceptorConfig() : config);
		this.dispatcher = dispatcher;
		this.predictorFactory = predictorFactory;
		this.pool = new NioProcessorPool(config, handler, dispatcher);
	}
	
	
	// ~ ------------------------------------------------------------------------------------------------------------
	
	
	/**
	 * Init nio acceptor to ready state for bind socket address.
	 * 
	 * @throws IOException
	 */
	private void init() throws IOException {
		selector = Selector.open();
		selectable = true;
		new AcceptThread().start();
	}
	
	@Override
	synchronized public void bind(int port) throws IOException {
		bind(new InetSocketAddress(port));
	}
	
	@Override
	synchronized public void bind(SocketAddress firstLocalAddress, SocketAddress... otherLocalAddresses) throws IOException {
		if (!this.selectable) {
            init();
        }
		
		if (firstLocalAddress == null) {
			throw new IllegalArgumentException("Need a local address to bind");
		}

		List<SocketAddress> localAddresses = new ArrayList<SocketAddress>(2);
		localAddresses.add(firstLocalAddress);

		if (otherLocalAddresses != null) {
			for (SocketAddress address : otherLocalAddresses) {
				localAddresses.add(address);
			}
		}
		
		bindAddresses.addAll(localAddresses);
		
		if (!bindAddresses.isEmpty()) {
			synchronized (lock) {
				// wake up for unblocking the select() to process binding addresses
				selector.wakeup();

				// wait for bind result
				wait0();
			}
		}
	}
	
	private void wait0() throws IOException {
		while (!this.endFlag) {
			try {
				lock.wait();
			} catch (InterruptedException e) {
				throw new IOException(e);
			}
		}

		// reset end flag
		this.endFlag = false;

		if (this.exception != null) {
			IOException e = exception;
			this.exception = null;
			throw e;
		}
	}
	
	private void bind0() {
		if (!bindAddresses.isEmpty()) {
			for (SocketAddress address : bindAddresses) {
				boolean success = false;
				try {
					bindByProtocol(address);
					success = true;
					
					LOG.debug("[CRAFT-ATOM-NIO] Bind |address={}|", address);
				} catch (IOException e) {
					exception = e;
				} finally {
					if (!success) {
						rollback();
						break;
					}
				}
			}
			
			bindAddresses.clear();
			
			// notify bind end
			synchronized (lock) {
				endFlag = true;
				lock.notifyAll();
			}
		}
	}
	
	/**
	 * Rollback already bound address
	 */
	protected void rollback() {
		 Iterator<Entry<SocketAddress, SelectableChannel>> it = boundmap.entrySet().iterator();
		 while(it.hasNext()) {
			 Entry<SocketAddress, SelectableChannel> entry = it.next();
			 try {
				 close(entry.getValue());
			 } catch (IOException e) {
				 LOG.warn("[CRAFT-ATOM-NIO] Rollback bind operation exception", e);
			 } finally {
				 it.remove();
			 }
		 }
	}
	
	private void close(SelectableChannel sc) throws IOException {
		if (sc != null) {
			SelectionKey key = sc.keyFor(selector);
			if (key != null) {
				key.cancel();
			}
			sc.close();
		}
	}
	
	@Override
	public void shutdown() {
		this.selectable = false;
		this.selector.wakeup();
	}
	
	private void shutdown0() throws IOException {
		// clear bind/unbind addresses cache
		this.bindAddresses.clear();
		this.unbindAddresses.clear();
		
		// close all opened server socket channel
		for (SelectableChannel sc : boundmap.values()) {
			close(sc);
		}
		
		// close acceptor selector
		this.selector.close();
		super.shutdown();
		LOG.debug("[CRAFT-ATOM-NIO] Shutdown acceptor successful");
	}
	
	@Override
	public Set<SocketAddress> getBoundAddresses() {
		return new HashSet<SocketAddress>(boundmap.keySet());
	}
	
	@Override
	synchronized public final void unbind(int port) throws IOException {
		unbind(new InetSocketAddress(port));
	}
	
	@Override
	synchronized public final void unbind(SocketAddress firstLocalAddress, SocketAddress... otherLocalAddresses) throws IOException {
		if (firstLocalAddress == null) {
			return;
		}

		List<SocketAddress> localAddresses = new ArrayList<SocketAddress>(2);
		if (boundmap.containsKey(firstLocalAddress)) {
			localAddresses.add(firstLocalAddress);
		}

		if (otherLocalAddresses != null) {
			for (SocketAddress address : otherLocalAddresses) {
				if (boundmap.containsKey(address)) {
					localAddresses.add(address);
				}
			}
		}
		
		unbindAddresses.addAll(localAddresses);
		
		if (!unbindAddresses.isEmpty()) {
			synchronized (lock) {
				// wake up for unblocking the select() to process unbinded addresses
				selector.wakeup();
				
				// wait for unbind result
				wait0();
			}
		}
	}
	
	/**
	 * Unbind at once according to specified type.
	 */
	private void unbind0() {
		if (!unbindAddresses.isEmpty()) {
			for (SocketAddress address : unbindAddresses) {
				try {
					if (boundmap.containsKey(address)) {
						SelectableChannel sc = boundmap.get(address);
						close(sc);
						boundmap.remove(address);
					}

					LOG.debug("[CRAFT-ATOM-NIO] Unbind |address={}|" + address);
				} catch (IOException e) {
					exception = e;
				} 
			}
			
			unbindAddresses.clear();
			
			// notify bind end
			synchronized (lock) {
				endFlag = true;
				lock.notifyAll();
			}
		}
	}
	
	private void accept() throws IOException {
		Iterator<SelectionKey> it = selector.selectedKeys().iterator();
		while (it.hasNext()) {
			SelectionKey key = it.next();
			it.remove();
			acceptByProtocol(key);
		}
	}
	
	/**
	 * Bind at once according to protocol type.
	 * 
	 * @param address
	 */
	protected abstract void bindByProtocol(SocketAddress address) throws IOException;	
	
	/**
	 * Accept at once according to protocol type.
	 * 
	 * @param key
	 * @return nio byte channel
	 */
	protected abstract NioByteChannel acceptByProtocol(SelectionKey key) throws IOException;
	
	
	// ~ ------------------------------------------------------------------------------------------------------------
	
	
	private class AcceptThread extends Thread {
		public void run() {
			while (selectable) {
				try {
					int selected = selector.select();
					
					if (selected > 0) {
						accept();
					}
					
					// bind addresses to listen
					bind0();
					
					// unbind canceled addresses
					unbind0();
				} catch (ClosedSelectorException e) {
					LOG.error("[CRAFT-ATOM-NIO] Closed selector exception", e);
					break;
				} catch (Exception e) {
					LOG.error("[CRAFT-ATOM-NIO] Unexpected exception", e);
					try {
						Thread.sleep(1000);
					} catch (InterruptedException ie) {}
				}
			}
			
			// if selectable == false, shutdown the acceptor
			try {
				shutdown0();
			} catch (Exception e) {
				LOG.error("[CRAFT-ATOM-NIO] Shutdown exception", e);
			}
		}
	}
	
	@Override
	public IoAcceptorX x() {
		NioAcceptorX x = new NioAcceptorX();
		x.setWaitBindAddresses(new HashSet<SocketAddress>(bindAddresses));
		x.setWaitUnbindAddresses(new HashSet<SocketAddress>(unbindAddresses));
		x.setBoundAddresses(new HashSet<SocketAddress>(boundmap.keySet()));
		IoReactorX rx = super.x();
		x.setNewChannelCount(rx.newChannelCount());
		x.setFlushingChannelCount(rx.flushingChannelCount());
		x.setClosingChannelCount(rx.closingChannelCount());
		x.setAliveChannelCount(rx.aliveChannelCount());
		return x;
	}

}
