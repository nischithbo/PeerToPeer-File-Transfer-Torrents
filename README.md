**P2P File Sharing Software**

A BitTorrent-like peer-to-peer file sharing application implemented in Java.

**Features**
  * Choking-unchoking mechanism
  * Handshake protocol
  * Bitfield exchange
  * Piece request and transfer
  * Preferred neighbors selection
  * Optimistically unchoked neighbor
  * Configuration
  * Common.cfg: Contains common properties for all peers
  * PeerInfo.cfg: Specifies peer information
    
**Usage**

  * Compile the project
  * Start peer processes in the order specified in PeerInfo.cfg
  * Run: peerProcess <peerID>

**Implementation Details**

  * Uses TCP for reliable communication
  * Implements custom message types (choke, unchoke, interested, etc.)
  * Periodic selection of preferred neighbors and optimistically unchoked neighbor
  * Piece selection strategy: Random (not rarest first)
    
**File Structure**
  
  * Executables and config files in working directory
  * Peer-specific files in peer_[peerID] subdirectories

**Logging** 

  * Generates detailed logs for each peer, including:
  * TCP connections
  * Changes in preferred neighbors
  * Choking/unchoking events
  * Piece downloads
  * Completion of file download
