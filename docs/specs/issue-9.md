# Spec Context — Issue 9

## Context and source evidence

[SC-09 / #9](https://github.com/stevdrey/fx-atspi-bridge/issues/9), baseline
`5dc4c68148ce7cfaaae20a9a6083e1297c6d3a12`. Component and geometry are absent.
SceneObserver owns JavaFX subscriptions and immutable projections; AtSpiObjects
defines schemas and AtSpiProvider owns native dispatch. Prerequisite contracts
from #2, #5 and #7 are implemented. Follow ADR 0001, system design and security
invariants. Preserve unrelated local issue-1/issue-16 documentation.

## Goal and non-goals

Implement read-only Component geometry in SCREEN (0), WINDOW (1), PARENT (2).
Respect rectangular bounds of node/ancestor clips and the scene viewport.
No pointer injection, screenshots, per-pixel picking, occlusion inference, focus,
scroll or geometry mutations. Three-dimensional bounds/transforms and custom
scene cameras are unsupported.

## Contracts and design

FX reads geometry through public JavaFX APIs and publishes framework-free,
immutable scalar records. Provider calls never schedule or wait for FX.
Component is stable on live nodes/windows, including sensitive controls; it is
absent on the application root and inert failed projections. Password text,
caret and skin nodes remain unobserved. Existing facade APIs and model
constructors remain compatible.

GetExtents(u)->(iiii), GetPosition(u)->ii, GetSize()->ii and Contains(iiu)->b
follow the upstream Component XML. Other Component methods return NotSupported.
Use JavaFX logical coordinates without output-scale multiplication. WINDOW
includes Scene.x/y; frame WINDOW origin is (0,0), with window dimensions.
SCREEN comes from JavaFX localToScreen/window coordinates. PARENT subtracts
the accessible parent's un-inherited-clipped bounding origin, in WINDOW axes;
a scene root uses the window origin. A frame's application parent has no
geometry, so frame PARENT is unsupported. Coordinate systems have independent
availability. Round edges outward, preserving empty dimensions; GetSize uses
WINDOW and rounding can cause a one-unit difference between systems. Contains
uses the same integer rectangle, with right/bottom edges excluded.

Intersect axis-aligned visual bounds with rectangular clip bounds and scene
viewport before rounding. Curved/rotated clips are bounding-box approximations,
not visual hit tests. Regions/controls use external layout bounds, avoiding
skin/content invalidations and excluding effects; other nodes use local visual
bounds. Hidden/minimized nodes have unavailable bounds; completely
clipped nodes have known empty bounds. Contains is false for both; bounds reads
on unavailable coordinates return NotSupported. Unsupported coordinate frames
return NotSupported, unknown uint32 types return InvalidArgs. Never manufacture
(0,0), clamp overflow or expose previous geometry as current.

Deduplicate inherited invalidations until flush. Per-flush identity caches share
ancestor clipping/origin work, with O(live targets) retention and deterministic
clearing. Observe only live exported targets and their clip objects. Dispose
subscriptions on clip/scene replacement, exclusion, detach and close. No timers,
periodic traversal or forced CSS/layout. Publish the latest observed layout;
tests wait for layout before comparison. Emit one BoundsChanged per touched id
with changed valid SCREEN bounds in each provider publication.

Derive the shared scene-to-screen translation from root points, not differences
of transformed bounding minima: the latter can introduce an extra pixel after
outward rounding of rotated/clipped geometry. Region layout bounds also prevent
indirect invalidations from password skins and excluded child content.

## Limits and failure modes

Reject non-finite/unrepresentable rectangles per coordinate system. Native
uint32 extraction uses the correct typed GLib getter; temporary child variants
are released in finally on the provider owner. No new retained invocations,
queues, arenas or native registrations beyond the Component interface. Reuse
existing sanitized errors, stale-reference and lifecycle contracts.

## Acceptance and verification

Unit tests cover rounding, overflow, empty/unknown coordinates, schema/cache,
events and unsigned native decoding. FX/private-bus tests cover transforms,
ancestor/own clip changes, hidden/minimized state, reparenting, replacement,
privacy, disposal and concurrent reads during modal actions. A separate lab
mode and bounded independent libatspi client compare post-layout FX reference
rectangles with remote results. Verify actual backend and tested scales/screens
on Fedora/GNOME/Wayland; unavailable native-Wayland/mixed-monitor cases remain
explicit gaps. Run focused checks then ./gradlew check under JDK 27. Compare
before/after clean revisions using the existing dispatch profile; no smoke-based
performance claims. Actual commands/results belong in docs/verification/issue-9.md.

## Rollout and documentation

Update README/system design with units, coordinate/clip/error contracts and
qualified environments. No new exported Java API. Reuse the issue branch and
publish a reviewed [Issue-9] PR. Do not close the issue while mandatory
acceptance evidence is unavailable.
