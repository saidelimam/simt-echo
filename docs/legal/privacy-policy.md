# SIMT ECHO Privacy Policy

**Effective Date:** November 4, 2026  
**Platform Version:** SIMT ECHO 2.0 (Physical Route Memory Platform)  
**System Architecture:** Offline-First KMP Edge Core with Consented Cloud AI Enrichment  

---

## 1. Introduction and Architectural Privacy Commitment

SIMT ECHO ("the Platform") is a privacy-first, offline-first mobile navigation system designed to transform individual indoor walkthroughs into portable, shareable spatial memories. Unlike conventional cloud-tethered mapping platforms that continuously track user coordinates or upload raw sensor streams to centralized servers, SIMT ECHO operates under a strict data-minimization paradigm.

As established in the system's foundational specifications, the Platform's core spatial tracking engine runs entirely on your local mobile device. Physical navigation data—comprising pedestrian dead reckoning (PDR), step detection, heading estimations, pressure changes, and relative route geometry—is processed locally within the device's execution layer. The Platform does not construct a global public map of private indoor facilities, nor does it maintain persistent user accounts, continuous background location logs, or centralized travel histories.

This Privacy Policy details the precise mechanisms governing data collection, on-device spatial processing, consented cloud AI enrichment, local peer-to-peer route transfers, and user-controlled data erasure.

### 1.1 Who is the controller, and how to reach us

The controller responsible for the processing described in this policy is **Simt Space, a company of Quazar Media** ("we", "us"), the maker of SIMT ECHO; its founder is Said Elimam. Privacy contact: **privacy@simt.app**. That mailbox answers every request listed in §5.4 and is the address to use for anything in this policy.

You do not need an account to use SIMT ECHO. We hold no profile, address book, e-mail address or payment record, and nothing here is sold or used to train a model.

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
On Android, before any image or video clip is prepared for cloud transmission, SIMT ECHO runs an on-device privacy pre-filter using bundled ML Kit face detection:
* **Automatic Scan & Exclusion:** Every approach frame is scanned locally for human faces.
* **Exclusion, not blurring:** A frame containing a detected face is excluded from the generated keyframes and video clips. The Platform does not blur or otherwise sanitize a frame it detected a face in; it drops the frame.
* **Manifest Flags:** Each keyframe and clip in the manifest carries a `faceFree` boolean. `faceFree: false` means the asset failed the filter and is never uploaded. Where the platform has no scanner (iOS today), the flag is **omitted rather than set to `true`**: absence means "not verified", and the Consent Sheet discloses that the frames are sent unscanned before anything leaves the phone.

### 3.2 Explicit Consent and Asset Selection
Cloud enrichment never occurs automatically. Before any data leaves the mobile device, the application presents a mandatory Consent Sheet detailing:
* The exact number of keyframe photos and silent, 5-second 480p approach clips to be processed.
* The specific cloud infrastructure and models utilized: Cloudflare Workers AI (Meta Llama 4 Scout, with Google Gemma 4 as its automatic fallback) and the optional Meta Model API, plus Meta Muse Spark 1.3 and SAM 3.1.
* **Two retention windows.** Enabling *Find landmarks while I walk* stages each checkpoint window to Cloudflare R2 as you walk, under a **1-hour** TTL; the processing job itself is created only after the walk, when the Consent Sheet shows the final photo/clip counts, and its media is retained for at most **24 hours**.
* An explicit disclosure confirming that raw route video remains private on the creator's phone and is never uploaded.
* **Network Data Gating:** Walk-time preparation uploads follow a data policy that defaults to Wi-Fi only. Under that default, a batch on a metered network—typically mobile data—is skipped entirely: no bytes are sent and nothing is partially staged. Roaming connections never upload, even if mobile data is permitted, and a network whose status cannot be determined fails closed as if offline.

Users retain the absolute right to decline cloud enrichment ("Not now"), in which case the Platform enables manual landmark creation ("Draw my own") directly on the device.

### 3.3 Data Minimization and Cloud Lifecycle Management
When cloud enrichment is authorized, data is processed in accordance with strict operational boundaries:
* **Temporary Cloud Storage:** Uploaded assets land in a Cloudflare R2 storage bucket under short-lived job directories (`jobs/<jobId>/in/`).
* **Automated Expiration:** All temporary cloud media and processing job directories are governed by an automated 24-hour Time-To-Live (TTL) lifecycle rule, after which they are permanently purged from server memory.
* **Immediate Deletion API:** Upon reviewing and approving generated landmarks, the route creator can trigger an immediate deletion command (`DELETE /v1/jobs/:id`), which instantly wipes all uploaded media and processing outputs from R2 storage. A staged walk-time session can be discarded the same way (`DELETE /v1/uploads/:id`).
* **Device record erasure:** The per-install credential described in §5.1 is registered in R2 as one record (`devices/<id>.json`) holding only a one-way verifier of the key and the times it was created and last used — never the key, an e-mail address or a device identifier. `DELETE /v1/device`, which Settings issues when you choose *Delete all*, erases that record; a record whose install has been idle for a year is erased automatically by its lifecycle rule.
* **Exclusion of Contributor / Training Models:** System workflows strictly utilize standard inference endpoints (Meta Llama 4 Scout with Google Gemma 4 as its fallback, Meta Muse Spark 1.3, and SAM 3.1). The Platform explicitly excludes contributor-tier models that retain or train on user-submitted content.

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
* **Mobile Data Control:** Creators may permit walk-time uploads over metered networks through the "Use mobile data" setting. The control is off by default (Wi-Fi only), is changed once in Settings rather than per walk, and applies to every subsequent walk until changed again. Roaming connections remain excluded in all cases.
* **Route Expiration Settings:** Creators can configure an expiration timestamp (e.g., 24 hours or 7 days) for shared bundles (`PrivacyInfo.expiresAtEpochMs`). Recipient devices automatically purge expired routes upon application launch.
* **Complete Storage Erasure:** Selecting *Delete all* within the application Settings purges all local routes, draft recordings, trial logs, cached assets, and local route-memory overlays from device storage, and, before doing so, erases what those records name on the server: any cloud processing job (`DELETE /v1/jobs/:id`), any resumable upload session and any walk-staged session (`DELETE /v1/uploads/:id`), and finally this install's device record (`DELETE /v1/device`) together with the local key. The server calls are best effort — if the network is unavailable the app says so, the local erase still completes, and the remaining objects expire under the 1-hour and 24-hour rules in §3.3.
* **Local Route Memory Isolation:** Secondary devices retain visual route-memory overlays locally to improve navigation performance on subsequent walks. This data remains strictly local, is never uploaded to cloud servers, and is erased whenever the parent route is deleted.

---

### 5.1 Lawful basis for each processing activity

| Processing | What it touches | Lawful basis |
| --- | --- | --- |
| On-device sensing, recording, navigation and route storage | Sensor telemetry, keyframes, view strips, route files — all on the phone | Performance of the service you asked for; no transfer occurs (Art. 6(1)(b)) |
| Cloud AI landmark enrichment, including walk-time staging | The keyframes and clips the Consent Sheet lists, sent to Cloudflare R2 and the inference endpoints in §5.2 | Your explicit consent, given per walk in the Consent Sheet and withdrawable at any time (Art. 6(1)(a)) |
| Peer-to-peer and relay sharing | The encrypted bundle you choose to share | Your consent, expressed by displaying or tapping the code (Art. 6(1)(a)) |
| Device credential and rate limiting | The key's salted verifier, the originating IP address in memory for the rate window | Legitimate interest in preventing abuse of a public endpoint (Art. 6(1)(f)) |
| Crash-free operation and safety guidance | Nothing leaves the device; no analytics, advertising or tracking SDK is present | Not applicable — no processing beyond the device |

We do not process special-category data for cloud enrichment: the on-device face filter excludes any frame containing a detected face (§3.1), and where no scanner exists the Consent Sheet discloses that frames are sent unscanned. Withdrawing consent stops future uploads; asking us to erase what was already sent is handled under §5.4.

### 5.2 Processors, storage locations and international transfers

| Recipient | Role | Data | Location |
| --- | --- | --- | --- |
| Cloudflare, Inc. | Processor: R2 object storage, Workers, Workers AI, Workflows | Uploaded keyframes and clips, manifests, job state, encrypted relay ciphertext | R2 bucket `echo-media`, EU (`WEUR`); inference on Cloudflare's network |
| Meta Platforms, Inc. | Processor for the optional Meta Model API (Llama 4 Scout fallback path, SAM 3.1, Muse Spark 1.3) | The same keyframes and clips, only when that provider is selected for a job | United States and Meta's global infrastructure |
| Google LLC (ML Kit) | On-device only | Camera frames scanned locally for faces | Never leaves the device |

No other party receives your media. Transfers outside the EEA rely on the European Commission's Standard Contractual Clauses (2021/914), and, where the recipient is certified, on the EU–US Data Privacy Framework. You can ask us for the safeguard that applies to a specific transfer.

### 5.3 Retention

Local data lives until you delete it. Cloud media lives for at most 24 hours after a job ends, walked-time staged media for at most 1 hour, relay ciphertext for 30 minutes, the daily spend ledger for 2 days, and the device record until you erase it or a year passes without a request from that install. Backups of the object store follow the same lifecycle rules.

### 5.4 Your rights

You have the right to access your data (Art. 15), to rectify it (Art. 16), to erase it (Art. 17), to restrict or object to processing (Arts. 18 and 21), to receive it in a portable form (Art. 20), and to withdraw consent at any time (Art. 7(3)) without affecting the lawfulness of processing before the withdrawal.

Because SIMT ECHO holds no account, most of these are exercised in the app and take effect immediately: *Delete all* erases the local store and the server objects and record it names (§5), the trials CSV and `.echo` export are your portability path, and revoking the Consent Sheet's opt-in stops further uploads. For anything else — including a copy of what a job held, or erasure of media whose job id you no longer have — write to **privacy@simt.app**; we answer within one month. Never send your device key: to find the objects an install owns we only need the approximate time of the upload and, if you have it, the job id shown when the request was made.

If you believe we have handled your data unlawfully you may lodge a complaint with a supervisory authority: the data-protection authority of your country of residence, place of work, or the place of the alleged infringement. For the controller's establishment that is the competent authority in the EU member state where Simt Space operates. Complaints can also be addressed to us first at the mailbox above.

## 6. Safety and Emergency Guidance Disclaimers

SIMT ECHO is designed strictly as a personal spatial memory tool for convenience and route-sharing among consenting individuals in everyday indoor environments.

**Life-Safety Non-Claim:** The Platform is NOT an emergency evacuation system, life-safety navigation aid, hazard-avoidance tool, or certified accessibility routing instrument. SIMT ECHO must never be relied upon in life-threatening situations, emergency evacuations, hazardous industrial environments, or for primary navigation by visually impaired individuals requiring certified accessibility tools.

---

## 7. Policy Updates and Contact Information

As SIMT ECHO continues to evolve across future software releases, this Privacy Policy may be updated to reflect enhancements in edge perception, cryptography, or platform features. Material changes will be indicated by updating the Effective Date at the top of this document.

For inquiries regarding the technical architecture, data protection mechanisms, or privacy governance of SIMT ECHO, please refer to the project repository documentation or contact the platform maintainers.
