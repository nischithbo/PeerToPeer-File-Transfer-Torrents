

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.BitSet;

public class PeerController implements Runnable {
	private Socket communicationChannel;
	private PeerManager coordinator;

	public boolean choked = true;
	private String peerControllerId;
	private boolean  channelEstablished = false;
	private boolean  intialized = false;
	private HandshakeMessage handshakeMessage;
	private volatile int chunkDownloadRate = 0;
	private volatile ObjectOutputStream out_stream;
	private volatile ObjectInputStream input_stream;


	public MessageSender messageSender;


	public PeerController(Socket communicationChannel, PeerManager coordinator) {
		this.communicationChannel = communicationChannel;
		this.coordinator = coordinator;
		this.messageSender = new MessageSender(this);
		initializeIOStreams();
		this.handshakeMessage = new HandshakeMessage(this.coordinator.getLocalPeerID());

	}

	public PeerManager getCoordinator() {
		return this.coordinator;
	}

	public void initializeIOStreams() {
		try {
			this.out_stream = new ObjectOutputStream(this.communicationChannel.getOutputStream());
			this.out_stream.flush();
			this.input_stream = new ObjectInputStream(this.communicationChannel.getInputStream());
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	public synchronized Socket getCommunicationChannel() {
		return this.communicationChannel;
	}

	public void setPeerControllerId(String pid) {
		this.peerControllerId = pid;
		this.intialized = true;
	}

	public void run() {
		try {
			byte[] msg = this.handshakeMessage.generateHandshakeMessage();
			this.out_stream.write(msg);
			this.out_stream.flush();
			while (true) {
				if (!this.channelEstablished) {
					// If no message is exchanged till now we start by exchaning the handshake and mark connection as estbalished for exchange of Actual message
					byte[] receivedData = new byte[32];
					this.input_stream.readFully(receivedData);
					this.processHandShakeMessage(receivedData);
					if (this.coordinator.hasSourceFile() || this.coordinator.getChunkAvailabilityOf(this.coordinator.getLocalPeerID()).cardinality() > 0) {
						this.messageSender.issueBitField();
					}
				}
				else {
					// wait until atleast one full message is available before reading
					while (this.input_stream.available() < 4) {
					}
					int payloadLength = this.input_stream.readInt();
					byte[] response = new byte[payloadLength];
					this.input_stream.readFully(response);
					char msgTypeValue = (char) response[0];
					ApplicationMessage msgObj = new ApplicationMessage();
					msgObj.parseMessage(payloadLength, response);
					//process the message based on the type of the message
					processMessageType(Constants.MessageType.fromCode(msgTypeValue), msgObj);

				}
			}
		}
		catch (SocketException e) {
			System.out.println("Socket exception");
			e.printStackTrace();
			try {
				this.coordinator.resetRequestedChunkInfo(this.peerControllerId);
				this.coordinator.getChunkAvailabilityOf(this.peerControllerId).set(0, this.coordinator.getChunkCount());

			}
			catch (Exception err){
				err.printStackTrace();


			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void processMessageType(Constants.MessageType msgType, ApplicationMessage am) {
		switch (msgType) {
			case CHOKE:
				handleChokeMessage();
				break;
			case UNCHOKE:
				handleUnchokeMessage();
				break;
			case INTERESTED:
				handleInterestedMessage();
				break;
			case NOT_INTERESTED:
				handleNotInterestedMessage();
				break;
			case HAVE:
				handleHaveMessage(am.getIndexFromMessageBody());
				break;
			case BITFIELD:
				handleBitFieldMessage(am.getBitFieldMessage());
				break;
			case REQUEST:
				handleRequestMessage(am);
				break;
			case PIECE:
				handlePieceMessage(am);
				break;
			default:
				System.out.println("Received other message");
		}
	}

	private void handleChokeMessage() {
		this.choked = true; // set peer as choked
		this.coordinator.resetRequestedChunkInfo(this.peerControllerId); // reset any chunk that was set a reuested to this peer
		this.coordinator.getClientLogger().storeChokingNeighborLog(this.peerControllerId);
	}

	private void handleUnchokeMessage() {
		this.coordinator.getClientLogger().storeUnchokedNeighborLog(this.peerControllerId);
		this.choked = false;
		// check if any can be requested to this peer
		int chunkIndex = this.coordinator.checkChunksRequested(this.peerControllerId, -1);
		if (chunkIndex == -1) {
			// if no chunk can be requested , check if peer has any chunk that is not yet present in local server if not send not Interested message
			if(!this.coordinator.checkIfInterested(this.peerControllerId, -1)) {
				this.messageSender.issueNotInterestedMessage();
			}
			else {
				this.messageSender.issueInterestedMessage();
			}
		} else {
			// if chunk can be requested set it as requested to the peer and send request message
			this.coordinator.setChunkRequestedInfo(chunkIndex, this.peerControllerId);
			this.messageSender.issueRequestMessage(chunkIndex);
		}
	}

	private void handleInterestedMessage() {
		// If interrested message is received add peer to interested list
		this.coordinator.appendToInterestedList(this.peerControllerId);
		this.coordinator.getClientLogger().storeInterestedLog(this.peerControllerId);
	}

	private void handleNotInterestedMessage() {
		// remove peer from interested list
		this.coordinator.setPeerAsNotInterested(this.peerControllerId);
		this.coordinator.getClientLogger().storeNotInterestedLog(this.peerControllerId);
	}

	private void handleHaveMessage(int pieceIndex) {
		// Update local record for the peer chinks and set it to true
		this.coordinator.updateChunkAvailability(this.peerControllerId, pieceIndex);
		// check if all peeer have all the chunks
		if (this.coordinator.areAllPeersDone()) {
			this.coordinator.cancelChokes();
		}
		//check if peer has any chunk that is not yet present in local server if not send not Interested message
		if (this.coordinator.checkIfInterested(this.peerControllerId, pieceIndex)) {
			this.messageSender.issueInterestedMessage();
		} else {
			this.messageSender.issueNotInterestedMessage();
		}
		this.coordinator.getClientLogger().storeHaveLog(this.peerControllerId, pieceIndex);
	}

	private void handleBitFieldMessage(BitSet bset) {
		// process BitFile message and send intreset or not interested message
		this.processBitFieldMessage(bset);
		if (!this.coordinator.hasSourceFile()) {
			if (this.coordinator.checkIfInterested(this.peerControllerId, -1)) {
				this.messageSender.issueInterestedMessage();
			} else {
				this.messageSender.issueNotInterestedMessage();
			}
		}
	}

	private void handleRequestMessage(ApplicationMessage msg) {
		// If a peer requests for a chunk, check if its in the unchoked ot optimistically unchocked peer and the send the chunk
		if (this.coordinator.getUnChokedPeerList().contains(this.peerControllerId)
				|| (this.coordinator.getOptimisticUnChokedPeer() != null && this.coordinator.getOptimisticUnChokedPeer().compareTo(this.peerControllerId) == 0)) {
			int chunkIndex = msg.getIndexFromMessageBody();
			this.messageSender.transmitPieceMessage(chunkIndex, this.coordinator.inputFromFileSync(chunkIndex));
		}
	}

	private void handlePieceMessage(ApplicationMessage msg) {
		// When a chunk is received write the chunk to the file
		int chunkIndex = msg.getIndexFromMessageBody();
		byte[] chunk = msg.getPieceMessageFromBody();
		this.coordinator.outputToFileSync(chunk, chunkIndex);
		this.coordinator.updateChunkAvailability(this.coordinator.getLocalPeerID(), chunkIndex);
		this.chunkDownloadRate++; // For determining unchoked list(Download rate)
		Boolean allPeersAreDone = this.coordinator.areAllPeersDone();
		this.coordinator.getClientLogger().storeDownloadedPieceLog(this.peerControllerId, chunkIndex, this.coordinator.getAvailableChunkCount());
		this.coordinator.setChunkRequestedInfo(chunkIndex, null);
		this.coordinator.sendHave(chunkIndex); // broadcast have message for the chunk
		if (this.coordinator.getChunkAvailabilityOf(this.coordinator.getLocalPeerID()).cardinality() != this.coordinator
				.getChunkCount()) {
			// if downlaoding all the chunks is not complete check for next request to be made
			int nextIndex = this.coordinator.checkChunksRequested(this.peerControllerId, chunkIndex);
			if(!this.choked){
				if (nextIndex != -1) {
					this.messageSender.issueRequestMessage(nextIndex);
				} else {
					if(!this.coordinator.checkIfInterested(this.peerControllerId, -1))
						this.messageSender.issueNotInterestedMessage();
					else
						this.messageSender.issueInterestedMessage();
				}}
			else{
				if(nextIndex != -1)
					this.coordinator.setChunkRequestedInfo(nextIndex, null);
			}
		} else {
			this.coordinator.getClientLogger().storeTheDownloadCompleteLog();
			if (allPeersAreDone) {
				// if all the peers have completed download of file start cleanup
				this.coordinator.cancelChokes();
			}
			else{
				this.messageSender.issueNotInterestedMessage();}
		}
	}
	public synchronized void transmitMessage(byte[] obj) {
		try {
			this.out_stream.write(obj);
			this.out_stream.flush();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}
	public void processHandShakeMessage(byte[] message) {
		try {// handle handshake message
			this.handshakeMessage.parseHandshakeMessage(message);
			this.peerControllerId = this.handshakeMessage.getPeerId();
			this.coordinator.addConnectedPeer(this, this.peerControllerId);
			this.coordinator.addJoinedThreads(this.peerControllerId, Thread.currentThread());
			this.channelEstablished = true;
			if (this.intialized) {
				this.coordinator.getClientLogger().tcpConnectionLogSenderGenerator(this.peerControllerId);
			}
			else {
				this.coordinator.getClientLogger().tcpConnectionLogReceiverGenerator(this.peerControllerId);
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void processBitFieldMessage(BitSet b) {
		this.coordinator.updateChunkBitsetAvailability(this.peerControllerId, b);
	}

	public int getChunkDownloadRateRate() {
		return this.chunkDownloadRate;
	}

	public void resetDownloadRate() {
		this.chunkDownloadRate = 0;
	}

}
