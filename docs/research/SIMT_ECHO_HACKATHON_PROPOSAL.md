# SIMT ECHO

> **Give unmapped places a memory.**  
> **One person explores. Another person finds the way.**

**Project type:** Hackathon product concept and implementation brief  
**Event:** Meta Global AI Developer Hackathon 2026  
**Primary theme:** Push the Frontier  
**Secondary theme:** Build for Your World  
**SIMT integration:** Track+, Iris, Connect, and the wider SIMT universe  
**Document version:** 1.0 — 1 November 2026  
**Status:** Proposed. This document specifies a hackathon MVP; it does not claim these features are already implemented.

---

## Executive summary

SIMT ECHO transforms a short smartphone walkthrough into a **shareable spatial memory**: a compact route graph combining motion-derived geometry, recognizable visual landmarks, and human-readable navigation cues.

A first person walks through a GPS-denied space (for example, an underground parking garage) and captures a route with SIMT. Meta's **SAM 3.1** identifies and tracks useful visual objects, while **Muse Spark 1.3** interprets the scenes and produces structured descriptions of candidate landmarks. SIMT's existing motion/sensor foundation supplies the route geometry; the AI supplies semantic checkpoints rather than fabricating coordinates.

A second person receives the saved route via a local transfer, aligns at the entrance, and follows guidance **without internet connectivity during replay**. The route creator may require connectivity during the AI-processing step; fully offline AI generation is not part of the MVP promise.

**The differentiator is not another AI map or chatbot. It is transferable experience of physical space:** one phone learns a route so a different phone can benefit from it.

### One-sentence pitch

> SIMT ECHO uses Meta's perception models and SIMT's offline-first spatial tracking to turn a real-world walkthrough into a route another person can navigate without GPS, beacons, a pre-existing floor plan, or internet access during playback.

### The winning demonstration

Show two physical phones in the same unmapped indoor route. Phone A records and publishes a memory; Phone B receives it, goes offline, identifies the starting checkpoint, and follows the same path to a destination it has never visited.

**Success means the transfer and navigation demonstrably work, not that the app displays impressive but unverified overlays.**

---

## 1. Problem and opportunity

### 1.1 The problem

Conventional map applications may become ineffective in underground garages, complex buildings, temporary venues, or other spaces where:

- GPS signals are weak or unavailable;
- useful indoor maps do not exist or are inaccurate;
- Wi-Fi or mobile data cannot be relied on;
- people need to share a route they just discovered.

A person who navigates such a place acquires useful knowledge, but that knowledge usually disappears when they leave. Drawing a floor plan or installing navigation infrastructure is too expensive for many temporary or small-scale scenarios.

### 1.2 The insight

**What if a place could be navigable because someone else had walked through it once?**

An inexpensive, permission-based route memory would make it possible to share an *experienced path*, even if nobody has mapped the whole building.

### 1.3 First target user

A visitor entering a multi-level underground parking area who needs to follow a route recorded by a companion or event organizer. For the MVP, constrain the route to a known 30–80 m indoor path with two or three turns and distinctive landmarks.

### 1.4 Product value

- A route can be created by walking, not by drawing a floor plan.
- A route can be transferred between devices rather than being locked to one user's travel history.
- Guidance remains available while the receiving device is offline.
- Landmarks help users orient themselves and provide recovery points when inertial tracking drifts.
- The resulting memory is small, portable, and owned/controlled by its creator.

---

## 2. Where ECHO fits in the SIMT universe

SIMT is already an offline-first spatial toolkit for Android and Wear OS. Its public product description includes GPS-denied **Track+**, camera-based overlays through **Iris**, and local-device linking through **Connect**. These are the strongest building blocks for ECHO [1].

| Existing SIMT capability | Role in ECHO | What the hackathon adds |
| --- | --- | --- |
| **Track+**: step, camera-motion, sensor and checkpoint fusion | Captures a traveled path and relative motion | Serializes the path as a shareable, re-playable route graph |
| **Iris**: live camera overlays | Potential visualization layer | Landmark-aware cues and route checkpoint UI |
| **Connect**: local-network device sharing | Local transport foundation | Sharing an ECHO route bundle with another device |
| **Haptics / offline guidance** | Device-native output | Directional navigation against transferred checkpoints |
| **SIMT's privacy-first design** | Local data ownership | Explicit capture, transfer, deletion, and consent for route memories |

### 2.1 Proposed universe positioning

A useful conceptual model is:

1. **SIMT Cosmos** — the design language and principles linking the universe.
2. **Simt Social** — people, real-world gatherings, and discoverable context.
3. **SIMT App** — movement, position, measurement, and device tools.
4. **SIMT ECHO / Physical Space (proposed)** — reusable, shared knowledge of physical routes.

This is a **proposed product architecture**, not a claim that these numbered worlds are official product names or shipped features. Over time, an authorized Simt Social host might attach an opt-in arrival route to an event, while SIMT App remains responsible for capture and navigation.

### 2.2 The key product leap

**Track+ today:** helps a person recover or revisit their own traveled route.  
**ECHO tomorrow:** enables that person's route knowledge to be transferred to another device and replayed by someone who did not travel with them.

That difference is the core innovation and should remain the centre of the pitch.

---

## 3. User experience and demo storyboard

### Scene 1 — An unmapped location

- Phone A enters an underground garage or indoor corridor.
- The creator taps **Record ECHO** and walks a short route.
- SIMT records timestamps, motion headings, estimated steps/distances, manually marked checkpoints, and selected camera frames.
- A red pillar, a lift door, and a clear intersection appear along the route.

**What the audience sees:** a regular phone navigating a place where an ordinary outdoor map is unhelpful.

### Scene 2 — The place becomes a memory

- The recorded capture is passed to the Meta-model pipeline with the user's consent.
- SAM 3.1 segments and tracks candidate landmarks across frames.
- Muse Spark 1.3 assigns useful descriptors, such as `red pillar near elevator`, and extracts any clearly legible directional information.
- ECHO combines these descriptors with the **sensor-derived** route to construct a lightweight graph.
- The creator reviews the route and confirms its starting point, turns, and destination.

**What the audience sees:** the walkthrough becomes a small, readable set of route nodes and landmarks, not a fictional AI-generated floor plan.

### Scene 3 — Transfer the memory

- Phone A creates a shareable ECHO bundle.
- Phone B pairs using a QR code or local-network discovery; the QR code can carry pairing metadata, while the bundle transfers over local Wi-Fi.
- Phone B receives the route graph and permitted landmark descriptions/images.
- The demo then deliberately disables mobile data and internet connectivity on Phone B.

**What the audience sees:** one phone passes its learned route to another.

### Scene 4 — A stranger follows it

- Phone B begins at the shared entrance checkpoint.
- SIMT gives turn and distance estimates and prompts for recognizable visual landmarks.
- The user confirms a landmark or the phone recognizes it locally, if such matching is robust enough for the prototype.
- Checkpoint confirmation re-anchors relative progress and helps mitigate accumulated drift.
- The user reaches the designated destination.

**What the audience sees:** a usable offline route, with haptic prompts and honest uncertainty, on a device that never recorded the route.

### The memorable moment

> **“I walked this place once. Now your phone remembers it too.”**

---

## 4. Meta AI integration: why the models matter

The hackathon's **Push the Frontier** theme calls for new capabilities grounded in physical systems [2]. ECHO's model use is substantive because it turns ordinary images into interpretable spatial checkpoints.

### 4.1 SAM 3.1: stable visual objects

**Input:** selected video frames or a short captured clip.  
**Task:** detect, segment, and track visually distinctive candidate objects—pillars, doors, signs, staircases, and similar scene elements.  
**Output:** tracked objects and masks or boxes, tied to observed timestamps.  
**Value:** candidate landmarks are visually grounded rather than invented.

SAM 3.1's published functionality includes object detection, segmentation, and tracking in images/video [3]. It **does not by itself solve cross-visit place recognition or indoor positioning**; that remains separate engineering work.

### 4.2 Muse Spark 1.3: semantic route checkpoints

**Input:** selected frames or clips, candidate object crops, timestamps, and route context.  
**Task:** describe landmarks, interpret visible signs where possible, flag ambiguity, and produce a strict JSON-style checkpoint description.  
**Output:** descriptions such as `large red pillar beside elevator`, `left turn after stairwell`, and an uncertainty flag.  
**Value:** the user's route becomes interpretable and easier to follow than a raw sensor trace.

Muse Spark 1.3 has native visual perception according to Meta's model page [4]. Treat generated descriptions as suggestions: the user and/or deterministic checks confirm what actually becomes guidance.

### 4.3 SIMT: geometry and navigation truth

SIMT records the walked path using its existing motion and sensor techniques. This provides a relative trajectory and turn/checkpoint sequence. AI **must not generate missing metric coordinates or silently rewrite the path**.

The central design principle is:

> **Sensors establish where the recorded route goes; AI helps identify what the traveler should recognize along it.**

### 4.4 Cloud versus offline boundaries

| Operation | MVP requirement | Connectivity |
| --- | --- | --- |
| Record a path and local frames | On-device SIMT workflow | Offline |
| Segment and interpret frames with SAM/Muse via Meta Model API | Model-assisted processing | Online during route creation |
| Confirm and save the route graph | On-device review | Offline after processing |
| Transfer the bundle to a nearby phone | Local pairing + LAN transfer | No internet; local network required |
| Follow the saved route | On-device motion/navigation plus stored checkpoints | Offline |
| Recognize a landmark again | Manual confirmation in base MVP; local matching only if proven | Offline for manual case |

**Do not claim** all AI processing is offline or that generic arbitrary buildings are immediately navigable.

---

## 5. Technical architecture

```text
 Phone A — Creator
 ┌─────────────────────────────────────────────────────┐
 │ SIMT Track+: sensors, steps, headings, checkpoint UI │
 │ Camera capture: frames/clips tied to timestamps      │
 └─────────────────────┬───────────────────────────────┘
                       │ consent + selected frames
                       v
          Meta Model API — Route understanding
       ┌──────────────────┬────────────────────────┐
       │ SAM 3.1          │ Muse Spark 1.3         │
       │ masks / tracking │ landmark descriptions  │
       └──────────────────┴────────────────────────┘
                       │ structured landmark proposals
                       v
          SIMT ECHO — Route graph assembler
       ┌────────────────────────────────────────────┐
       │ Deterministic sensor route + confirmations │
       │ checkpoints + descriptors + confidence    │
       └──────────────────┬─────────────────────────┘
                          │ reviewed route bundle
                    QR pairing / local LAN
                          │
                          v
 Phone B — Navigator
 ┌─────────────────────────────────────────────────────┐
 │ Import bundle -> align start -> follow turns         │
 │ Re-anchor at landmarks -> reach destination         │
 │ Offline replay, haptic / text / optional Iris cues   │
 └─────────────────────────────────────────────────────┘
```

### 5.1 Proposed modules

| Module | Responsibility | Delivery priority |
| --- | --- | --- |
| `EchoCapture` | Collect timestamped Track+ trajectory, frames and marked turns | P0 |
| `EchoAIAdapter` | Send consented frame samples to Meta, parse/validate outputs | P0 |
| `EchoGraphBuilder` | Combine sensor path with approved landmark candidates | P0 |
| `EchoBundle` | Compact versioned export/import format | P0 |
| `EchoTransfer` | Local-network transfer with QR pairing | P0 |
| `EchoNavigator` | Start alignment, path progress, cues and re-anchoring | P0 |
| `EchoLandmarkMatcher` | Optional automatic local visual matching | P1 / stretch |
| `EchoIrisOverlay` | Optional camera-route rendering | P1 / stretch |

`P0` means required to claim an end-to-end hackathon MVP; `P1` is deliberately expendable.

### 5.2 Proposed route graph

An ECHO graph is a **traveled route**, not a general-purpose indoor map. Nodes represent meaningful points; ordered edges represent the recorded transitions between them. Every node can carry an estimate and supporting evidence.

```json
{
  "schema_version": 1,
  "route_id": "example-route",
  "name": "Garage entrance to elevator",
  "coordinate_system": "relative_to_confirmed_start",
  "capture_mode": "simt_track_plus",
  "nodes": [
    {
      "id": "n0",
      "type": "start",
      "description": "Main entrance",
      "confirmation": "creator_verified"
    },
    {
      "id": "n1",
      "type": "landmark_turn",
      "description": "Red pillar beside elevator",
      "direction_after_node": "left",
      "landmark_source": "sam3.1_and_muse_spark1.3",
      "confirmation": "creator_verified"
    },
    {
      "id": "n2",
      "type": "destination",
      "description": "Elevator entrance",
      "confirmation": "creator_verified"
    }
  ],
  "edges": [
    { "from": "n0", "to": "n1", "relative_motion_ref": "segment-01" },
    { "from": "n1", "to": "n2", "relative_motion_ref": "segment-02" }
  ],
  "privacy": {
    "route_shared_by_creator": true,
    "raw_video_included": false
  }
}
```

This example is **illustrative, not an implemented schema**. Real segments also need timestamps, motion samples or normalized path points, accuracy/confidence metadata, versioning, and integrity validation. Avoid storing raw video by default.

### 5.3 Reliability and recovery

Pedestrian dead reckoning accumulates error. Users can start from slightly different positions and may hold phones differently. For the hackathon:

1. Require a **confirmed start checkpoint**, not an assumed absolute indoor coordinate.
2. Use short, distinctive routes with clearly visible turns.
3. Prefer user-confirmed landmark matches to speculative automatic relocalization.
4. Re-anchor route progress only after matching a trustworthy checkpoint.
5. Display **“Location uncertain — confirm landmark”** rather than a confident wrong instruction.
6. Expose a **restart at checkpoint** option and stop directional claims if alignment fails.

A polished manual fallback is stronger than an unstable claim of fully automatic localization.

### 5.4 Security and privacy

- Get explicit consent before capturing or sending frames to Meta Model API.
- Do not share full route data automatically, or turn private locations into a public map without permission.
- Prefer cropped landmarks and short retained metadata rather than faces, license plates, or raw videos.
- Review route content for sensitive places and obvious personal information before export.
- Encrypt and integrity-check transferred bundles where supported; avoid publishing location history by default.
- Provide **delete**, **expire**, and **do not share** controls.
- Warn that shared routes may disclose internal layouts; limit public discovery for private buildings.
- ECHO is **not an emergency evacuation, accessibility certification, or life-safety navigation system**.

---

## 6. Hackathon-winning strategy

### 6.1 Alignment with the event

The Meta Global AI Developer Hackathon offers two participation tracks—indie developer and startup—and two themes: **Build for Your World** and **Push the Frontier** [2]. ECHO best fits **Push the Frontier**, because it adds a device-level capability grounded in the physical world.

The strongest story is a working, surprising demonstration of **spatial memory transfer**, not generic visual object detection. If entering the startup track, also emphasize the existing SIMT app and credible product integration; if entering indie, emphasize originality, technical ambition, and reliable execution. The correct track depends on the team's accepted registration and official rules.

### 6.2 Working judging rubric

Published analyses of the event waiver report the following project-judging breakdown [5]. **Confirm against the rules accepted by your team**, as the public event landing page itself does not display these weights.

| Criterion | Reported weight | What ECHO must demonstrate |
| --- | ---: | --- |
| Real-world impact | 30% | A real visitor successfully navigates an unmapped space. |
| Use of Meta AI tooling | 25% | SAM/Muse are central to creating recognizable, useful landmarks. |
| Demo | 25% | Two actual phones, live route sharing, offline replay, visible outcome. |
| Technical depth and execution | 20% | Sensor-derived path, structured pipeline, uncertainty handling, reproducible tests. |

### 6.3 What distinguishes the entry

- **Unexpected capability:** a route learned by one device can be used by another.
- **Grounded AI:** AI interprets the environment while deterministic sensor tracking handles trajectory.
- **Strong baseline:** SIMT already offers relevant offline/GPS-denied features.
- **Visible proof:** the audience can watch one device record, transfer, then guide another.
- **Credible constraints:** no promise of universal building reconstruction or flawless autonomous localization.

### 6.4 What *not* to build for this submission

Exclude from the MVP:

- complete 3D building reconstruction;
- automatic mapping of every room, floor, staircase and elevator;
- open-ended routing to arbitrary indoor destinations;
- cloud-synced public libraries of indoor building layouts;
- fully autonomous landmark matching unless validated;
- evacuation, hazard avoidance, or other safety-critical guidance;
- a large social feature rollout.

These can be roadmap candidates, not deadline-critical obligations.

---

## 7. MVP scope and acceptance criteria

### 7.1 Minimum viable end-to-end flow

A successful first build should let someone:

1. Record a short route on Phone A.
2. Select/confirm two or three route landmarks with meaningful Meta AI output.
3. Review a route graph showing turns, checkpoints, and destination.
4. Export the graph and transfer it to Phone B over local Wi-Fi.
5. Disable internet connectivity on Phone B.
6. Confirm the same start checkpoint.
7. Follow readable and haptic directions to the destination.
8. Recover if route confidence degrades or a landmark cannot be identified.

### 7.2 Concrete acceptance tests

| ID | Test | Pass condition |
| --- | --- | --- |
| A1 | Meta-model perception | At least two useful landmarks appear with traceable input frames and editable descriptions. |
| A2 | Graph integrity | Route path follows recorded Track+ movement rather than AI-invented coordinates. |
| A3 | Transfer | Phone B imports and validates a bundle without an internet request. |
| A4 | Offline replay | Phone B reaches the destination while mobile internet is disabled. |
| A5 | Start re-alignment | Wrong start position is detected, rejected, or requires explicit confirmation. |
| A6 | Uncertainty fallback | If lost, the app asks to confirm/restart instead of silently improvising. |
| A7 | Privacy | Creator can preview and choose exactly what to export. |
| A8 | Reproducibility | A short setup guide and a recorded end-to-end demo are available. |

### 7.3 Measurements for the judges

Run the same course with **sensor-only Track+ guidance** and with **ECHO landmark checkpoints**. Record results honestly rather than assuming ECHO wins.

Suggested measures:

- successful arrivals / total attempts;
- time to destination;
- number of manual corrections or checkpoint confirmations;
- estimated drift or path deviation at premarked checkpoints;
- number of missed or mistaken landmark matches;
- processing/transfer time and generated bundle size.

Collect at least a few repeat trials across two phones. Document device types, route length, lighting conditions, and known failures. Small, transparent evidence is more persuasive than unsupported accuracy claims.

---

## 8. Implementation plan: November 1–4, 2026

As of this document's date, the published submission deadline is **Wednesday, November 4, 2026, 9:00 p.m. Eastern Time (ET)** — **Thursday, November 5, 2026, 3:00 a.m. Paris time (CET)** [2]. The application deadline was **October 25**, so this plan assumes the participant or team has already been accepted. Recheck the organizers' page before submitting, as dates may change.

| Date | Priority | Deliverable | Exit criteria |
| --- | --- | --- | --- |
| **Nov 1** | P0 | Capture/export layer | Record a real 30–80 m path; export timestamped motion + selected frames/checkpoints. |
| **Nov 2** | P0 | SAM/Muse route understanding | Identify two or three useful landmarks; parse structured results; creator approves graph. |
| **Nov 3** | P0 | Sharing + navigator | Import a bundle on Phone B and follow the path offline with turn/checkpoint guidance. |
| **Nov 4** | P0 | Test, stabilize, submit | Run repeat trials; capture a real demo; write limitations; submit early and verify receipt. |

### Suggested order of engineering work

**First:** make transfer + offline playback work with a manually authored route graph. This proves the core product loop before model integration.

**Second:** integrate Meta models into the capture workflow, replacing manual landmark descriptions with AI-generated candidates that users confirm.

**Third:** improve visual polish and add optional camera overlays only after the path from record → transfer → navigate is reliable.

### Blockers and contingency plans

| Risk | Likely impact | Fallback |
| --- | --- | --- |
| Model API rate limit, latency, or outage | Slow route creation | Preprocess sample media, cache permitted results, and demo the authentic outputs transparently. |
| Unreliable landmark detection | Poor navigation instructions | Manual checkpoint selection/editing, keeping Meta-generated candidates visible. |
| Motion drift | Wrong turn/distance estimate | Frequent confirmed checkpoints; short route; uncertainty banner. |
| Phone/device incompatibility | Demo failure | Test and pin two known devices early; prepare a recorded backup. |
| LAN pairing problem | No second-phone demonstration | Implement a versioned bundle import/export fallback while showing how the normal local transfer works. |
| Time squeeze | Incomplete prototype | Drop AR overlays and automatic visual matching before cutting end-to-end navigation. |

---

## 9. Demo and submission plan

### 9.1 Recommended video narrative (approximately 90–120 seconds)

| Segment | Visual | Voice-over / point |
| --- | --- | --- |
| **0:00–0:12** | A corridor or garage; ordinary mapping context fails | “Some places have no usable map.” |
| **0:12–0:32** | Phone A records the route in SIMT | “What if a single walkthrough could change that?” |
| **0:32–0:52** | Real SAM masks, Muse labels and a reviewed route graph | “Meta's models turn visual observations into recognizable checkpoints.” |
| **0:52–1:08** | Transfer from Phone A to Phone B; visibly disable internet | “Now the route is portable—and playback is offline.” |
| **1:08–1:42** | Phone B follows turns and arrives at target | “A different phone can navigate a place it never explored.” |
| **1:42–2:00** | Brief architecture, metrics, and product vision | “One person explores. Another person finds the way.” |

Do not edit cuts to conceal failed navigation. If replay is not stable, state what worked and what required manual confirmation.

### 9.2 Judge-facing evidence package

- A working Android build on the two demo devices.
- A short video of the real end-to-end workflow.
- A simple architectural diagram and clear explanation of Meta-model roles.
- The minimal route graph schema plus sanitized sample bundle.
- Reproducible test notes and truthful measurements.
- A README with setup, permissions, required connectivity and limitations.
- Clear disclosure of **pre-existing SIMT functionality** versus **hackathon-developed ECHO functionality**.
- Verification of the selected team's submission obligations and official rules.

### 9.3 Demo rules of thumb

- Show *two* real phones: the transfer is the surprising innovation.
- Keep the route short and reliably repeatable.
- Display model-generated landmark artifacts and their source frames to prove AI use.
- Make the offline transition obvious and truthful: the second phone still uses local sensors, not Meta cloud inference, to replay the saved route.
- Explain what happens when the system is uncertain.
- Present measured improvement only if trials substantiate it.

---

## 10. Suggested submission copy

### Project title

**SIMT ECHO — Give Unmapped Places a Memory**

### Tagline

**One person explores. Another person finds the way.**

### Short pitch

What if a phone could learn a route simply by walking through a GPS-denied place, then share that route with another person who has never been there? SIMT ECHO converts a walkthrough into a portable spatial memory using Meta SAM 3.1, Muse Spark 1.3 and SIMT's offline-first movement tracking. The result is a small route graph that a second phone can follow without internet during navigation—no beacons or existing floor plan required.

### Full submission description

Every day, people enter spaces where GPS fails and digital maps stop: underground parking garages, complex buildings and temporary venues.

What if a smartphone could learn a route simply by walking through it, then share that knowledge with someone who has never been there?

**SIMT ECHO** turns a short walkthrough into a reusable spatial memory. It combines smartphone motion tracking with **Meta's SAM 3.1** for visual landmark segmentation and **Muse Spark 1.3** for semantic understanding, producing a lightweight navigation graph.

Another person can receive that graph and follow the **recorded** route without GPS, internet connectivity during playback, installed beacons, or an existing floor plan. The navigator starts from a confirmed checkpoint and uses landmarks to stay oriented. Where the system is uncertain, it requests confirmation rather than inventing directions.

We're building on **SIMT**, an existing offline-first spatial toolkit, to explore a new capability: transferring knowledge of a physical place between devices.

**Our vision is simple: The world shouldn't need to be mapped in advance for people to find their way through it.**

> **Use this description only to the extent the implementation actually supports the claims by submission time.** Replace future-tense or planned functionality with measured results and honest limitations before publishing.

---

## 11. Long-term product roadmap

The hackathon proves one route; the broader product could grow into a private, opt-in spatial knowledge network.

### Phase 1 — ECHO personal sharing

- Send selected navigation memories to trusted people.
- Revisit imported routes later without an internet connection.
- Attach notes and checkpoint confirmations.
- Support optional Wear OS haptic guidance.

### Phase 2 — ECHO for events

- Give approved attendees arrival paths for temporary venues.
- Let organizers publish routes to registration desks or meeting locations.
- Integrate with Simt Social only through explicit, limited-purpose sharing.

### Phase 3 — Collaborative physical memories

- Compare repeated traversals of the same route, with consent.
- Propose corrections when checkpoints have moved or become invalid.
- Improve confidence across devices while preserving data minimization.

### Phase 4 — Wider indoor navigation

- Explore constrained route networks, multiple entrances and floors.
- Research on-device landmark matching and stronger relocalization.
- Consider accessibility-specific experiences only after dedicated validation, accessible UX testing and safety review.

A public map of private indoor layouts is **not** a default or automatic outcome of this roadmap.

---

## 12. Decisions to lock before implementation

- [ ] Confirm accepted entrant(s), chosen track and the official rules.
- [ ] Select the two Android phones and one repeatable indoor test route.
- [ ] Identify the Track+ export hooks already available in the actual SIMT codebase.
- [ ] Choose the minimum camera capture and retention policy.
- [ ] Verify SAM 3.1 and Muse Spark 1.3 access using the participant's Meta Model API account.
- [ ] Freeze a strict model-output schema and validation logic.
- [ ] Build/test offline route import and replay before visual embellishments.
- [ ] Confirm creator preview, permission, and delete flows.
- [ ] Test baseline versus ECHO, including failure conditions.
- [ ] Record demo, document the pre-existing code boundary, and submit before deadline.

---

## Sources and verification notes

Sources checked on **1 November 2026**:

1. [SIMT official site — offline-first toolkit, Track+, Iris, Connect](https://simt.app/)
2. [Meta Global AI Developer Hackathon — themes, tracks, models, key dates](https://dev.meta.ai/events/global-hackathon)
3. [Meta SAM 3.1 model overview — segmentation and tracking](https://dev.meta.ai/models/sam-3-1)
4. [Meta Muse Spark 1.3 model overview — multimodal visual reasoning](https://dev.meta.ai/models/muse-spark)
5. [Secondary event-waiver summary — reported judging weights](https://bootshell.com/meta-global-ai-developer-hackathon-2026-complete-guide-rules-prizes)

**Source-of-truth rule:** The official Meta event page and the official rules accepted by participating teams take precedence over this planning document and secondary summaries. The official event page confirms the November 4, 9:00 p.m. ET submission deadline, models and themes; the precise 30/25/25/20 scoring weights are taken from reports of the event waiver, not directly reproduced on the official landing page.

**Technical claims:** The ability to create, transfer, or navigate an ECHO route is proposed. SIMT's public site documents adjacent existing capabilities, but it does not establish that the ECHO product already exists. The model docs establish capability categories, not route-navigation accuracy. All success metrics, failure rates, and qualitative outcomes must be obtained from the actual prototype.

---

## Final positioning

**Do not pitch ECHO as “AI indoor Google Maps.”** That suggests impossible scope and weakens credibility.

Pitch it as the **first shareable spatial memory** in the SIMT universe, demonstrated by two phones completing one real route:

> **You don't need to map the whole world. Sometimes you just need to pass on the path you discovered.**
