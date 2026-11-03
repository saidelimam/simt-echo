# SIMT ECHO Privacy Policy

**Effective Date:** November 3, 2026  
**Platform Version:** SIMT ECHO 2.0 (Physical Route Memory Platform)  
**System Architecture:** Offline-First KMP Edge Core with Consented Cloud AI Enrichment  

---

## 1. Introduction and Architectural Privacy Commitment

SIMT ECHO ("the Platform") is a privacy-first, offline-first mobile navigation system designed to transform individual indoor walkthroughs into portable, shareable spatial memories. Unlike conventional cloud-tethered mapping platforms that continuously track user coordinates or upload raw sensor streams to centralized servers, SIMT ECHO operates under a strict data-minimization paradigm.

As established in the system's foundational specifications, the Platform's core spatial tracking engine runs entirely on your local mobile device. Physical navigation data—comprising pedestrian dead reckoning (PDR), step detection, heading estimations, pressure changes, and relative route geometry—is processed locally within the device's execution layer. The Platform does not construct a global public map of private indoor facilities, nor does it maintain persistent user accounts, continuous background location logs, or centralized travel histories.

This Privacy Policy details the precise mechanisms governing data collection, on-device spatial processing, consented cloud AI enrichment, local peer-to-peer route transfers, and user-controlled data erasure.

---

## 2. On-Device Data Collection and Local Processing

When you record or navigate a route using SIMT ECHO, your smartphone's physical hardware sensors generate local motion and visual telemetry. This information is processed strictly within the application's local sandbox storage directory and is never transmitted off-device without explicit, real-time user authorization.

### 2.1 Motion and Sensor Telemetry
To compute indoor progress without relying on external GPS signals, the Platform accesses built-in hardware sensors:
* **Inertial Measurement Unit (IMU):** The accelerometer and gyroscope sample motion at high frequencies (up to 50 Hz) to detect footsteps (using a Weinberg step-length estimator) and calculate relative yaw headings using game rotation vectors.
* **Barometric Sensor:** Relative atmospheric pressure changes are sampled to identify vertical level transitions (such as stairs or elevators) without assuming sea-level calibration.
* **Magnetometer:** Local magnetic field magnitudes and vertical vector components are stored in 0.5-meter spatial buckets to construct orientation-invariant magnetic signatures for route relocalization.

All sensor telemetry remains strictly confidential and is stored locally in temporary draft files (`capture.json`) or compiled route containers (`route.json`).

### 2.2 Camera Imagery and Visual Descriptors
Camera frames are accessed exclusively while the application is active in the foreground during route recording or navigation alignment:
* **Recording Ledger & Keyframe Curation:** As a creator walks a route, an on-device quality ledger evaluates decimated luma frames for sharpness (Laplacian variance), exposure, rotational stability, and visual novelty. The sharpest frames are saved locally as keyframe stills.
* **Monocular View Strips:** For route-conditioned velocity tracking, the device generates compact 96-byte 1D view strips (comprising 64 column values and 32 row values) every 2 meters. These view strips represent mathematical spatial gradients and cannot be reverse-engineered or reconstructed into human-viewable photographs.
* **Visual Fingerprints:** The Platform extracts int8 low-dimensional visual place descriptors (256-dimensional projections) at key checkpoints and waypoints to support local position verification. Visual fingerprints are stored exclusively within encrypted route bundles.

---

## 3. Consented Cloud AI Landmark Enrichment

SIMT ECHO incorporates advanced multimodal AI perception to translate raw visual frames into semantic navigation checkpoints (such as identifying a "red pillar beside the elevator"). Cloud processing is strictly opt-in and operates under a multi-layered privacy shield.

### 3.1 Pre-Upload On-Device Face Privacy Filter
Before any image or video clip is prepared for cloud transmission, SIMT ECHO executes an on-device privacy pre-filter utilizing bundled ML Kit face detection algorithms:
* **Automatic Scan & Exclusion:** Every approach frame is scanned locally for human faces.
* **Redaction & Filtering:** Frames containing detected faces or personal identifying features are automatically excluded from generated video clips or blurred prior to packaging.
* **Manifest Verification:** The resulting manifest carries explicit boolean flags (`faceFree: true`) confirming that privacy verification passed on the local device.

### 3.2 Explicit Consent and Asset Selection
Cloud enrichment never occurs automatically. Before any data leaves the mobile device, the application presents a mandatory Consent Sheet detailing:
* The exact number of keyframe photos and silent, 5-second 480p approach clips to be processed.
* The specific cloud infrastructure utilized (Cloudflare Workers AI and optional Meta Model API providers).
* An explicit disclosure confirming that raw route video remains private on the creator's phone and is never uploaded.

Users retain the absolute right to decline cloud enrichment ("Not now"), in which case the Platform enables manual landmark creation ("Draw my own") directly on the device.

### 3.3 Data Minimization and Cloud Lifecycle Management
When cloud enrichment is authorized, data is processed in accordance with strict operational boundaries:
* **Temporary Cloud Storage:** Uploaded assets land in a Cloudflare R2 storage bucket under short-lived job directories (`jobs/<jobId>/in/`).
* **Automated Expiration:** All temporary cloud media and processing job directories are governed by an automated 24-hour Time-To-Live (TTL) lifecycle rule, after which they are permanently purged from server memory.
* **Immediate Deletion API:** Upon reviewing and approving generated landmarks, the route creator can trigger an immediate deletion command (`DELETE /v1/jobs/:id`), which instantly wipes all uploaded media and processing outputs from R2 storage.
* **Exclusion of Contributor / Training Models:** System workflows strictly utilize standard inference endpoints (such as Meta Llama 4 Scout, Muse Spark 1.3, and SAM 3.1). The Platform explicitly excludes contributor-tier models that retain or train on user-submitted content.

---

## 4. Peer-to-Peer Route Sharing and Cryptography

SIMT ECHO enables route creators to transfer completed spatial memories directly to secondary devices ("Followers"). Sharing mechanisms are designed to maintain end-to-end security and prevent unauthorized intercept.

### 4.1 Encrypted Route Containers (ECX1)
Shared route memories are packaged into versioned, encrypted binary containers:
* **AES-256-GCM Encryption:** All route geometry, landmark descriptions, cropped images, and visual perception profiles are encrypted using AES-256 in Galois/Counter Mode with a unique 32-byte symmetric key (`ECX1` container specification).
* **SHA-256 Integrity Verification:** Every bundle includes a SHA-256 cryptographic digest to detect payload tampering or corruption during transit.

### 4.2 Bearer Credentials and Sharing Channels
The decryption key and pairing token are encoded into a URI parameter (`simt-echo://r?v=1...`) presented via:
* **High-Contrast QR Codes:** Displayed on the creator's screen for direct camera scanning.
* **Near Field Communication (NFC) Tap:** Emitted via foreground Android Host Card Emulation (HCE) using a proprietary ISO-DEP APDU protocol (`F04543484F01`).
* **Local Area Network (LAN) Transport:** Bytes are transferred directly between devices over local Wi-Fi or mobile hotspot connections using ephemeral Ktor HTTP sessions that terminate automatically upon transfer completion.

Because the QR code and NFC payload act as bearer credentials, displaying a code or tapping phones constitutes an intentional act of sharing.

### 4.3 Zero-Knowledge Cloud Relay Fallback
In environments where local network isolation prevents direct peer-to-peer LAN transfers, the Platform offers an optional zero-knowledge relay service:
* **End-to-End Ciphertext Relay:** The creator's device uploads the AES-256-GCM encrypted payload (`E`) to an ephemeral Cloudflare R2 relay storage bucket.
* **Zero Server Visibility:** The decryption key (`k`) is never transmitted to the relay server; it remains strictly within the creator's QR code or NFC payload. The server stores ciphertext only and cannot inspect, read, or decrypt the route contents.
* **Ephemeral Code Expiry:** Relay storage codes expire automatically within 30 minutes, after which uncollected ciphertext is permanently erased.

### 4.4 Plain File Export (.echo)
Users may explicitly export an unencrypted route container (`.echo` format) for manual file sharing. Prior to generating a plain file export, the application presents a prominent privacy warning highlighting that plain exports do not contain AES encryption and advising users to share plain files only with trusted parties. Plain exports exclude perception data by default unless explicitly opted in by the creator.

---

## 5. User Privacy Controls, Expiration, and Data Erasure

SIMT ECHO provides comprehensive governance controls over stored spatial data:

* **Creator Asset Preview & Toggling:** Prior to exporting or sharing a route, creators can review an Export Preview sheet displaying all included landmark crops and toggle individual assets off. Items flagged for potential privacy risks (such as background text or screens) default to an excluded state.
* **Route Expiration Settings:** Creators can configure an expiration timestamp (e.g., 24 hours or 7 days) for shared bundles (`PrivacyInfo.expiresAtEpochMs`). Recipient devices automatically purge expired routes upon application launch.
* **Complete Storage Erasure:** Selecting "Delete All Data" within the application Settings immediately and permanently purges all local routes, draft recordings, trial logs, cached assets, and local route-memory overlays from device storage.
* **Local Route Memory Isolation:** Secondary devices retain visual route-memory overlays locally to improve navigation performance on subsequent walks. This data remains strictly local, is never uploaded to cloud servers, and is erased whenever the parent route is deleted.

---

## 6. Safety and Emergency Guidance Disclaimers

SIMT ECHO is designed strictly as a personal spatial memory tool for convenience and route-sharing among consenting individuals in everyday indoor environments.

**Life-Safety Non-Claim:** The Platform is NOT an emergency evacuation system, life-safety navigation aid, hazard-avoidance tool, or certified accessibility routing instrument. SIMT ECHO must never be relied upon in life-threatening situations, emergency evacuations, hazardous industrial environments, or for primary navigation by visually impaired individuals requiring certified accessibility tools.

---

## 7. Policy Updates and Contact Information

As SIMT ECHO continues to evolve across future software releases, this Privacy Policy may be updated to reflect enhancements in edge perception, cryptography, or platform features. Material changes will be indicated by updating the Effective Date at the top of this document.

For inquiries regarding the technical architecture, data protection mechanisms, or privacy governance of SIMT ECHO, please refer to the project repository documentation or contact the platform maintainers.
