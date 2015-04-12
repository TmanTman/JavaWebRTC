

class WebRtcClient {

	private PeerConnectionFactory factory;
	private LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<PeerConnection.IceServer>();
	private MediaConstraints pcConstraints = new MediaConstraints();
	MediaStream lMS;
	protected Peer peer;
	protected final MessageHandler messageHandler = new MessageHandler();
	private String TAG = "webrtc";
	public String username;
	private Handler mHandler;
	private PubnubMessenger pubMes;

	private interface Command {
		void execute(String peerId, JSONObject payload) throws JSONException;
	}

	private class CreateOfferCommand implements Command {
		public void execute(String peerId, JSONObject payload)
				throws JSONException {
			Log.d(TAG, "CreateOfferCommand");
			peer.pc.createOffer(peer, pcConstraints);
		}
	}

	private class CreateAnswerCommand implements Command {
		public void execute(String peerId, JSONObject payload)
				throws JSONException {
			Log.d(TAG, "CreateAnswerCommand");
			if (payload == null)
				Log.d(TAG, "payload is null");
			if (peer == null)
				Log.d(TAG, "peer is null");
			if (payload.getString("type") == null)
				Log.d(TAG, "type is null");
			if (payload.getString("sdp") == null)
				Log.d(TAG, "sdp is null");
			String sdpDesc = payload.getString("sdp");
			Log.d("paramstest", "SDP in CreateAnswerCommand: " + sdpDesc);
			SessionDescription sdp = new SessionDescription(
					SessionDescription.Type.fromCanonicalForm(payload
							.getString("type")), sdpDesc);
			peer.pc.setRemoteDescription(peer, sdp);
			peer.pc.createAnswer(peer, pcConstraints);
		}
	}

	private class SetRemoteSDPCommand implements Command {
		public void execute(String peerId, JSONObject payload)
				throws JSONException {
			Log.d(TAG, "SetRemoteSDPCommand");
			String sdpDesc = payload.getString("sdp");
			//String sdpDesc = preferILBC((String)payload.getString("sdp"));
			Log.d("paramstest", "SDP on setRemoteSDPCommand: " + sdpDesc);
			SessionDescription sdp = new SessionDescription(
					SessionDescription.Type.fromCanonicalForm(payload
							.getString("type")), sdpDesc);
			peer.pc.setRemoteDescription(peer, sdp);
		}
	}

	private class AddIceCandidateCommand implements Command {
		public void execute(String peerId, JSONObject payload)
				throws JSONException {
			Log.d(TAG, "AddIceCandidateCommand");
			PeerConnection pc = peer.pc;
			if (pc.getRemoteDescription() != null) {
				IceCandidate candidate = new IceCandidate(
						payload.getString("id"), payload.getInt("label"),
						payload.getString("candidate"));
				pc.addIceCandidate(candidate);
			}
			Log.d(TAG, "Added Ice candidate");
		}
	}
	
	public void sendMessage(String to, String type, JSONObject payload)
			throws JSONException {
		JSONObject message = new JSONObject();
		message.put("to", to);
		message.put("from", username);
		message.put("type", type);
		message.put("payload", payload);
		pubMes.message(message);
	}

	private class MessageHandler implements PubnubMessenger.PubnubEvents {

		private HashMap<String, Command> commandMap;

		public MessageHandler() {

			this.commandMap = new HashMap<String, Command>();
			commandMap.put("init", new CreateOfferCommand());
			commandMap.put("offer", new CreateAnswerCommand());
			commandMap.put("answer", new SetRemoteSDPCommand());
			commandMap.put("candidate", new AddIceCandidateCommand());
		}

		@Override
		public void onEvent(JSONObject pJson) {
			Log.d(TAG, "In onEvent(JSON) method");
			try {
				Log.d(TAG, "Before reading from JSON");
				String from = pJson.getString("from");
				String type = pJson.getString("type");
				Log.d(TAG, "MessageHandler.onEvent() from: " + from + " type: "
						+ type);
				JSONObject payload = null;
				payload = pJson.getJSONObject("payload");
				// if caller does not exist or is not not current peer, make it
				// the current peer
				if (peer == null || !peer.myContactsUsername.equals(from)) {
					peer = new Peer(from);
				}

				// At this stage, either the old peer still exists or a new one
				// has been created.
				commandMap.get(type).execute(from, payload);

			} catch (JSONException e) {
				Log.d(TAG, "Error encountered: " + e.toString());
				e.printStackTrace();
			}
		}
	}

	public void establishDataChannelAtPeer() {
		peer.establishDataChannel();
	}

	class Peer implements SdpObserver, PeerConnection.Observer {
		PeerConnection pc;
		private DataChannel dc;
		private DataChannel.Observer dcObs;
		public String myContactsUsername;
		
		class NStatsObserver implements StatsObserver {
			
			@Override
			public void onComplete(StatsReport[] arg0) {
				Log.d(TAG, "Logging NStatsObserver with tag ParamsTest");
				Log.d("statsobs",  "StatsReport length: " + arg0.length);
				for (int i = 0; i < arg0.length; i++){
					Log.d("statsobs", "Report " + i + ": " + arg0[i].toString());
				}
				
			}
		}

		// SDPOBSERVER interface
		// This is the onCreate of the offer or answer of the sdp transaction
		@Override
		public void onCreateSuccess(final SessionDescription sdp) {
			try {
				JSONObject payload = new JSONObject();
				payload.put("type", sdp.type.canonicalForm());
				payload.put("sdp", sdp.description);
				sendMessage(myContactsUsername, sdp.type.canonicalForm(),
						payload);
				pc.setLocalDescription(Peer.this, sdp);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}

		@Override
		// Called on success of Set{Local, Remote} Description()
		public void onSetSuccess() {
			Log.d(TAG, "onSetSuccess listener triggered");
		}

		@Override
		public void onCreateFailure(String s) {
		}

		@Override
		public void onSetFailure(String s) {
		}

		// PEERCONNECTION.OBSERVER interface
		@Override
		public void onSignalingChange(
				PeerConnection.SignalingState signalingState) {
			Log.d(TAG, "Signaling state: " + signalingState.name());
		}

		@Override
		public void onIceConnectionChange(
				PeerConnection.IceConnectionState iceConnectionState) {
			if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
				onStatusChanged("DISCONNECTED");
			}
		}

		@Override
		public void onIceGatheringChange(
				PeerConnection.IceGatheringState iceGatheringState) {
			if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
				Log.d(TAG, "IceGatheringState COMPLETE");
			}
		}

		@Override
		public void onIceCandidate(final IceCandidate candidate) {
			try {
				JSONObject payload = new JSONObject();
				payload.put("label", candidate.sdpMLineIndex);
				payload.put("id", candidate.sdpMid);
				payload.put("candidate", candidate.sdp);
				sendMessage(peer.myContactsUsername, "candidate", payload);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}

		@Override
		public void onError() {
		}

		@Override
		public void onAddStream(MediaStream mediaStream) {
			Log.d(TAG, "onAddStream " + mediaStream.label());
			// This app only accepts AudioTracks within the media stream (no
			// video streams)
			// Should only be a single audio track in the mediastream
			if (!mediaStream.audioTracks.isEmpty()) {
				Log.d(TAG, "Received audiotracks");
				mediaStream.audioTracks.getFirst();
				// How do I add this mediaStream/audiotrack to play through the
				// local speaker
				// Like it would in a normal telephone call?
			}
		}

		@Override
		public void onRemoveStream(MediaStream mediaStream) {
			Log.d(TAG, "onRemoveStream called");
		}

		@Override
		public void onDataChannel(DataChannel remoteDataChannel) {
			Log.d(TAG, "Remote Datachannel established");
			this.dc = remoteDataChannel;
			dc.registerObserver(new DcObserver());
			Log.d(TAG, "registered Dc Observer");
		}

		@Override
		public void onRenegotiationNeeded() {
			Log.d(TAG, "onRegenotian Needed");
		}

		public void establishDataChannel() {
			// Create Datachannel with unreliable transport
			DataChannel.Init dcOptions = new DataChannel.Init();
			dcOptions.ordered = false;
			dc = pc.createDataChannel("DocCHatDataChannelLabel", dcOptions);
			Log.d(TAG, "Data channel established");
			if (dc != null) {
				Log.d(TAG, "Adding observer to DC channel");
				DcObserver dcObs = new DcObserver();
				Log.d(TAG, "Observerver assigned");
				dc.registerObserver(dcObs);
				Log.d(TAG, "Observer registered to DC");
			} else {
				Log.d(TAG, "Datachannel is null");
			}
		}

		// Peer constructor - marked just for easier recognition
		public Peer(String pUsername) {
			Log.d(TAG, "new Peer: " + pUsername);
			Log.d("paramstest", "PC constraints: " + pcConstraints.toString());
			this.pc = factory.createPeerConnection(iceServers, pcConstraints,
					this);
			this.myContactsUsername = pUsername;

			Log.d(TAG, "Now adding local stream");
			// Add the audio stream from initializeMedia
			pc.addStream(lMS, new MediaConstraints());

			onStatusChanged("CONNECTING");
			
			pc.getStats(new NStatsObserver(), lMS.audioTracks.getFirst());
		}

	}

	// Call a person with the indicated username
	public void call(String contactsUsername) {
		try {
			Log.d(TAG, "Attempting to call + " + contactsUsername);
			peer = new Peer(contactsUsername);
			// Create datachannel from the one side
			// Local data stream
			// DataChannel.Init dcOptions = new DataChannel.Init();
			// dcOptions.ordered = false;
			peer.dc = peer.pc.createDataChannel("testDataChannelLabel",
					new DataChannel.Init());
			Log.d(TAG, "Adding observer to DC channel");
			DcObserver dcObs = new DcObserver();
			Log.d(TAG, "Observerver assigned");
			peer.dc.registerObserver(dcObs);
			Log.d(TAG, "Obersver registered to DC");

			// Create initial SDP and, once it's created (SDP observer
			// onCreated), send the sdp to the other person
			messageHandler.commandMap.get("init").execute(username, null);
		} catch (JSONException e) {
			Log.d(TAG, "Exception: " + e.toString());
		}
	}


	public void onStatusChanged(final String newStatus) {
		Log.d(TAG, newStatus);
	}

	public void initializeMedia() {
		// Hopefully, this intercepts the audio coming in from the local phones
		// microphone
		AudioTrack audioTrack = factory.createAudioTrack("Track",
				factory.createAudioSource(pcConstraints));
		lMS = factory.createLocalMediaStream("StreamLabel");
		lMS.addTrack(audioTrack);
		// Now, this stream does not have to be played by the local Android
		// devices Speakers
	}
	

	public void send(float x, float y, int state) {

		PathPoint point = new PathPoint((int)x, (int)y, state);
		byte[] yourBytes = null;

		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ObjectOutput out = null;
		try {
			out = new ObjectOutputStream(bos);
			out.writeObject(point);
			yourBytes = bos.toByteArray();
		} catch (IOException e) {
			Log.d(TAG, "Error: " + e.toString());
		} finally {
			try {
				if (out != null) {
					out.close();
				}
			} catch (IOException ex) {
				Log.d(TAG, "Error second catch: " + ex.toString());
			}
			try {
				bos.close();
			} catch (IOException ex) {
				Log.d(TAG, "Error third catch: " + ex.toString());
			}
		}
		if (yourBytes == null)
			Log.d(TAG, "yourBytes was zero");
		ByteBuffer buf = ByteBuffer.wrap(yourBytes);
		DataChannel.Buffer dcBuf = new DataChannel.Buffer(buf, true);
		Log.d(TAG, "Sending buffer via webrtc");
		peer.dc.send(dcBuf);
		Log.d(TAG, "Successfully sent buffer");
		
	}

	public WebRtcClient(String pUsername, Handler pHandler) {
		// Set the username, username will be a filter for messages on channel
		this.username = pUsername;

		this.mHandler = pHandler;

		factory = new PeerConnectionFactory();

		pubMes = new PubnubMessenger(this);

		iceServers.add(new PeerConnection.IceServer("stun:23.21.150.121"));
		iceServers.add(new PeerConnection.IceServer(
				"stun:stun.l.google.com:19302"));

		pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"OfferToReceiveAudio", "true"));
		pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"OfferToReceiveVideo", "false"));
		pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
				"DtlsSrtpKeyAgreement", "true"));
		pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
				"googImprovedWifiBwe", "true"));


		initializeMedia();
	}

	private class DcObserver implements DataChannel.Observer {

		public DcObserver() {
			Log.d(TAG, "Creating new DcObserver");
		}

		@Override
		public void onMessage(DataChannel.Buffer dcBuf) {
			Log.d(TAG, "received message");

			// Java deserialization of object
			ByteBuffer bbuf = dcBuf.data;
			byte[] bytes = new byte[bbuf.capacity()];
			bbuf.get(bytes, 0, bytes.length);
			ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
			ObjectInput in = null;
			PathPoint point = null;
			try {
				in = new ObjectInputStream(bis);
				point = (PathPoint) in.readObject();
			} catch (IOException e) {
				Log.d(TAG, "Error: " + e.toString());
			} catch (ClassNotFoundException e) {
				Log.d(TAG, "Error: " + e.toString());
			} finally {
				try {
					bis.close();
				} catch (IOException ex) {
					Log.d(TAG, "Error second catch in Rx: " + ex.toString());
				}
				try {
					if (in != null) {
						in.close();
					}
				} catch (IOException ex) {
					Log.d(TAG, "Error third catch in Rx: " + ex.toString());
				}
			}
			if (point == null) Log.d(TAG, "RECEIVED NULL JSON");
			else {
				Message msg = null;
				try {
					msg = mHandler.obtainMessage();
				} catch (Exception e) {
					Log.d(TAG, "RECEIVED NULL FROM HANDLER");
				}
				if (msg == null) Log.d(TAG, "RECEIVED NULL MESSAGE");
				else {
					msg.obj = point;
					mHandler.sendMessage(msg);
				}
				Log.d(TAG, "Received point with x=" + point.x + ", y=" + point.y
						+ ", state=" + point.state);
			}
			
		}

		@Override
		public void onStateChange() {
			Log.d(TAG, "Data channel state change");
		}
	}

	public static int void main (String[] args) {
		String username = "Caller"
		//OR String username = "Receiver"

		mWebRtcClient = new WebRtcClient(username, h);
		if (username.equals("Caller"))
				mWebRtcClient.call("Receiver");
	}

}
