# SIMT ECHO Terms of Use

**Effective Date:** November 3, 2026  
**Platform Version:** SIMT ECHO 2.0 (Physical Route Memory Platform)  
**Governing Framework:** KMP Edge Software License & Service Agreement  

---

## 1. Acceptance of Terms and Service Overview

Welcome to SIMT ECHO ("the Platform"), an offline-first mobile application platform created by Said Elimam that converts personal indoor walkthroughs into shareable, portable spatial route memories. By installing, accessing, or utilizing the SIMT ECHO mobile application, scanning SIMT ECHO QR codes, receiving route bundles via Near Field Communication (NFC) or local network transfers, or utilizing associated Cloudflare and Meta AI enrichment services, you agree to be bound by these Terms of Use ("Terms").

If you do not agree to all provisions contained within these Terms, you must immediately cease accessing the Platform and uninstall the application software.

---

## 2. Description of Platform and License Grant

SIMT ECHO provides software tools designed to record indoor walking paths using mobile device hardware sensors (accelerometers, gyroscopes, barometers, and camera frames), enrich those paths with semantic landmark descriptions via artificial intelligence, and transfer encrypted route bundles (`.echo` containers) to secondary devices for local offline replay.

### 2.1 License Grant
Subject to your continuous compliance with these Terms, SIMT ECHO grants you a limited, personal, non-exclusive, non-transferable, revocable license to install and execute the application software on compatible mobile hardware (Android and supported edge devices) solely for personal, non-commercial, or authorized organizational route-sharing purposes.

### 2.2 Intellectual Property & Route Ownership
* **User Ownership of Route Memories:** You retain full ownership of all spatial routes, walk logs, custom landmark annotations, and photographs recorded by your device. SIMT ECHO does not claim ownership over your recorded physical walkthroughs or custom route bundles.
* **Platform Intellectual Property:** The SIMT ECHO software name, source code, KMP core architecture, Cosmos design system, Luminous motion interface, logo, sigil generation algorithms, and underlying documentation are the exclusive intellectual property of Said Elimam and platform licensors.

---

## 3. User Obligations and Conduct Constraints

As a condition of your license to use SIMT ECHO, you agree to abide by clear operational boundaries when recording, enriching, sharing, or navigating physical routes.

### 3.1 Route Creator Responsibilities
When recording an indoor walkthrough ("Route Creator"), you represent and warrant that:
* **Lawful Access:** You have legal access and authorization to enter, walk through, and record visual imagery within the physical facility or location.
* **No Trespassing or Surveillance:** You will not use the Platform to record restricted, classified, confidential, or private areas without authorization, nor will you use the visual capture capabilities for unlawful surveillance or voyeurism.
* **Landmark Verification:** You are responsible for reviewing and verifying all AI-suggested landmark proposals (`AI_SUGGESTED`) prior to saving and sharing a route bundle. You agree not to distribute route bundles containing misleading, deceptive, or unsafe navigation instructions.

### 3.2 Route Follower Responsibilities
When replaying a shared spatial memory ("Route Follower"), you acknowledge and agree that:
* **Situational Awareness:** You must maintain active visual and physical awareness of your surroundings at all times. You must not look exclusively at your mobile device screen while moving.
* **Environmental Precedence:** Physical obstacles, hazard markings, locked doors, crowd conditions, and emergency signs in the real world always supersede directional arrows, distance readouts, or landmark prompts displayed by the Platform.

---

## 4. AI Feature Limitations and Human Verification Requirement

SIMT ECHO integrates multimodal artificial intelligence models—including Meta Llama 4 Scout, Muse Spark 1.3, and SAM 3.1 via Cloudflare Worker pipelines—to propose recognizable landmark descriptions (such as identifying a "red pillar").

### 4.1 Strict Geometry Boundary
In accordance with the fundamental architectural contracts of the Platform, artificial intelligence components are strictly prohibited from generating, modifying, adding, or deleting physical spatial coordinates, path points, or turn angles. All metric coordinates and trajectory geometries are derived exclusively from hardware sensors.

### 4.2 Informational Nature of AI Proposals
AI-generated landmark descriptions, bounding boxes, segmentation masks, and approach cues represent automated probabilistic suggestions. They do not constitute guaranteed physical facts or certified architectural statements. All AI candidates require explicit review and confirmation by the Route Creator (`CREATOR_VERIFIED`) before becoming trusted navigation anchors.

---

## 5. Offline Replay, Peer-to-Peer Transfer, and Security

SIMT ECHO is designed as an offline-first system. Navigation playback operates locally on the receiving device without requiring mobile data or active internet connections.

### 5.1 Transport Security & Encryption
* **Encrypted Containers (ECX1):** Route bundles transferred via QR codes, NFC taps, or local area network (LAN) connections are sealed using AES-256-GCM encryption with SHA-256 integrity verification.
* **Bearer Credential Duty:** QR codes and NFC tap invitations contain the encryption key and access token. You acknowledge that sharing or displaying a QR code allows anyone who scans it to decrypt and access that specific route bundle.

### 5.2 Zero-Knowledge Relay Usage
When utilizing the optional Cloudflare R2 internet relay service to transfer routes across isolated networks, you acknowledge that the relay server stores encrypted ciphertext only. The Platform cannot decrypt or recover your route contents on the server, nor can it recover lost decryption keys if a QR code is destroyed.

---

## 6. Absolute Exclusion of Life-Safety and Emergency Use

**CRITICAL NOTICE:** SIMT ECHO IS NOT AN EMERGENCY EVACUATION SYSTEM, LIFE-SAFETY NAVIGATION TOOL, HAZARD DETECTION SYSTEM, OR CERTIFIED ACCESSIBILITY GUIDANCE SERVICE.

You explicitly agree that you will not use SIMT ECHO:
* For emergency egress, fire evacuation, or disaster response routing.
* For navigating hazardous industrial, military, or dangerous environments.
* As a primary or sole navigation aid for individuals with severe visual impairments or mobility disabilities requiring certified accessible pathways.

The Platform calculates relative inertial motion using consumer smartphone sensors, which inherently accumulate drift over distance. Inertial tracking errors, lighting variations, or environmental shifts can result in position uncertainty.

---

## 7. Disclaimer of Warranties

TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, SIMT ECHO IS PROVIDED ON AN "AS IS" AND "AS AVAILABLE" BASIS, WITH ALL FAULTS AND WITHOUT WARRANTY OF ANY KIND.

SAID ELIMAM AND PLATFORM LICENSORS EXPRESSLY DISCLAIM ALL WARRANTIES, EXPRESS, IMPLIED, OR STATUTORY, INCLUDING BUT NOT LIMITED TO IMPLIED WARRANTIES OF MERCHANTABILITY, SATISFACTORY QUALITY, FITNESS FOR A PARTICULAR PURPOSE, ACCURACY, QUIET ENJOYMENT, AND NON-INFRINGEMENT.

WITHOUT LIMITING THE FOREGOING, THE PLATFORM DOES NOT WARRANT THAT THE NAVIGATION CUES WILL BE UNINTERRUPTED, ACCURATE, COMPLETELY RELIABLE, ERROR-FREE, OR THAT INERTIAL DRIFT WILL NOT OCCUR DURING ROUTE PLAYBACK.

---

## 8. Limitation of Liability

TO THE FULLEST EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT SHALL SAID ELIMAM, SIMT ECHO DEVELOPERS, OR AFFILIATED CONTRIBUTORS BE LIABLE FOR ANY INCIDENTAL, SPECIAL, INDIRECT, CONSEQUENTIAL, OR PUNITIVE DAMAGES WHATSOEVER—INCLUDING, WITHOUT LIMITATION, DAMAGES FOR PERSONAL INJURY, PROPERTY DAMAGE, SLIP AND FALL ACCIDENTS, TRESPASS FINES, LOSS OF DATA, BUSINESS INTERRUPTION, OR ANY OTHER COMMERCIAL DAMAGES OR LOSSES—ARISING OUT OF OR RELATED TO YOUR USE OF OR INABILITY TO USE SIMT ECHO, HOWEVER CAUSED, REGARDLESS OF THE THEORY OF LIABILITY (CONTRACT, TORT, OR OTHERWISE), EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

---

## 9. Modifications to Terms and Service Termination

Platform maintainers reserve the right to modify these Terms at any time. Updated Terms will be published within the application repository or release packages. Your continued use of SIMT ECHO following the posting of revised Terms constitutes your acceptance of those changes.

Your rights under these Terms will terminate automatically without notice if you fail to comply with any provision. Upon termination, you must cease all use of the Platform and delete all local copies of the application software.

---

## 10. Governing Law and Severability

These Terms shall be governed by and construed in accordance with applicable general software licensing principles, without giving effect to conflict of law provisions. If any provision of these Terms is found to be unenforceable or invalid by a court of competent jurisdiction, that provision shall be limited or eliminated to the minimum extent necessary, and the remaining provisions shall remain in full force and effect.
