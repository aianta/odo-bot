package ca.ualberta.odobot.debugger;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The Trajectory Debugger's timelines: what a person recorded, cut, annotated or imported, in the order they are shown.
 *
 * <p>An entity is either <em>live</em>, still held by a recording session (only the last entity of a recording can
 * still change: an input change or DOM effect is only ever folded into the latest entity), or <em>frozen</em>, its row
 * saved in its own folder, see {@link EntityFolder}. A live entity is frozen once a newer one arrives or its session
 * ends.</p>
 *
 * <p>Events of a recording go to the <em>active</em> timeline, at its end. If there is none when one arrives, a new
 * timeline is created and made active.</p>
 *
 * <p>Its methods are synchronized: the debugger changes it on its context, and imports it on a worker thread. Every
 * change bumps {@link #version()}.</p>
 */
public final class Workspace {

    public static final String UNTITLED = "Untitled timeline";

    /**
     * A recording session's handle on an entity it still holds.
     *
     * @param liveId the session's id for the entity.
     */
    public record LiveRef(String sessionId, long liveId) {
        JsonObject toJson() {
            return new JsonObject().put("sessionId", sessionId).put("liveId", liveId);
        }

        static LiveRef fromJson(JsonObject json) {
            return json == null ? null : new LiveRef(json.getString("sessionId"), json.getLong("liveId"));
        }
    }

    public static final class Entity {
        private final String id;
        private String notes;
        private JsonObject summary;
        private LiveRef live;

        Entity(String id, JsonObject summary, LiveRef live, String notes) {
            this.id = id;
            this.summary = summary == null ? new JsonObject() : summary;
            this.live = live;
            this.notes = notes;
        }

        public String id() {
            return id;
        }

        public String notes() {
            return notes;
        }

        public JsonObject summary() {
            return summary;
        }

        /**
         * @return the session that still holds the entity, or null once it is frozen.
         */
        public LiveRef live() {
            return live;
        }

        public boolean isLive() {
            return live != null;
        }

        JsonObject toJson() {
            return new JsonObject().put("id", id).put("notes", notes).put("summary", summary)
                    .put("live", live == null ? null : live.toJson());
        }

        static Entity fromJson(JsonObject json) {
            return new Entity(json.getString("id"), json.getJsonObject("summary"), LiveRef.fromJson(json.getJsonObject("live")),
                    json.getString("notes"));
        }
    }

    public static final class Timeline {
        private final String id;
        private String title;
        private String notes;
        private final long createdAt;
        private final JsonObject source;
        private final List<Entity> entities = new ArrayList<>();

        Timeline(String id, String title, String notes, long createdAt, JsonObject source) {
            this.id = id;
            this.title = title;
            this.notes = notes;
            this.createdAt = createdAt;
            this.source = source;
        }

        public String id() {
            return id;
        }

        public String title() {
            return title;
        }

        public String notes() {
            return notes;
        }

        public long createdAt() {
            return createdAt;
        }

        /**
         * @return where an imported timeline came from (its database and trajectory), or null for a recording.
         */
        public JsonObject source() {
            return source;
        }

        public List<Entity> entities() {
            return List.copyOf(entities);
        }

        JsonObject toJson() {
            return new JsonObject().put("id", id).put("title", title).put("notes", notes).put("createdAt", createdAt)
                    .put("source", source)
                    .put("entities", new JsonArray(entities.stream().map(Entity::toJson).toList()));
        }

        static Timeline fromJson(JsonObject json) {
            Timeline timeline = new Timeline(json.getString("id"), json.getString("title", UNTITLED), json.getString("notes"),
                    json.getLong("createdAt", 0L), json.getJsonObject("source"));
            for (Object o : json.getJsonArray("entities", new JsonArray())) {
                timeline.entities.add(Entity.fromJson((JsonObject) o));
            }
            return timeline;
        }
    }

    /**
     * What a cut did.
     *
     * @param second the timeline holding the entities after the cut, or null if nothing followed the cut entity.
     */
    public record Cut(Timeline first, Timeline second) {
    }

    private final List<Timeline> timelines = new ArrayList<>();
    private String activeTimelineId;
    private long version;
    private final LongSupplier clock;

    public Workspace() {
        this(System::currentTimeMillis);
    }

    Workspace(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized List<Timeline> timelines() {
        return List.copyOf(timelines);
    }

    public synchronized String activeTimelineId() {
        return activeTimelineId;
    }

    private void changed() {
        version++;
    }

    // ---------------------------------------------------------------- lookups

    public synchronized Optional<Timeline> timeline(String id) {
        return timelines.stream().filter(t -> t.id.equals(id)).findFirst();
    }

    private Timeline requireTimeline(String id) {
        return timeline(id).orElseThrow(() -> new IllegalArgumentException("No timeline " + id));
    }

    public synchronized Optional<Entity> entity(String id) {
        return timelines.stream().flatMap(t -> t.entities.stream()).filter(e -> e.id.equals(id)).findFirst();
    }

    /**
     * @return the timeline that holds the entity.
     */
    public synchronized Optional<Timeline> timelineOf(String entityId) {
        return timelines.stream().filter(t -> t.entities.stream().anyMatch(e -> e.id.equals(entityId))).findFirst();
    }

    public synchronized Optional<Entity> liveEntity(String sessionId, long liveId) {
        LiveRef ref = new LiveRef(sessionId, liveId);
        return timelines.stream().flatMap(t -> t.entities.stream()).filter(e -> ref.equals(e.live)).findFirst();
    }

    /**
     * @return every entity a session still holds.
     */
    public synchronized List<Entity> liveEntities(String sessionId) {
        return timelines.stream().flatMap(t -> t.entities.stream())
                .filter(e -> e.live != null && e.live.sessionId().equals(sessionId)).toList();
    }

    // ---------------------------------------------------------------- recording

    /**
     * A recording session reported a new entity, or a change to one: a new one goes at the end of the active timeline
     * (created if there is none), a change updates its summary wherever it is now.
     *
     * @return the entity, or null for a change to an entity that was deleted.
     */
    public synchronized Entity onLiveEntity(String sessionId, long liveId, JsonObject summary, boolean isNew) {
        Optional<Entity> existing = liveEntity(sessionId, liveId);
        if (existing.isPresent()) {
            existing.get().summary = summary;
            changed();
            return existing.get();
        }
        if (!isNew) {
            return null;
        }
        Timeline active = activeTimelineId == null ? null : timeline(activeTimelineId).orElse(null);
        if (active == null) {
            active = addTimeline(null);
            activeTimelineId = active.id;
        }
        Entity entity = new Entity(newId(), summary, new LiveRef(sessionId, liveId), null);
        active.entities.add(entity);
        changed();
        return entity;
    }

    /**
     * The session no longer holds the entity: its row is saved in the entity's folder now.
     */
    public synchronized void frozen(Entity entity, JsonObject summary) {
        entity.live = null;
        if (summary != null) {
            entity.summary = summary;
        }
        changed();
    }

    // ---------------------------------------------------------------- editing

    /**
     * Add a blank timeline at the end. It is not made active.
     *
     * @param title its title, or null for {@value #UNTITLED}.
     */
    public synchronized Timeline createTimeline(String title) {
        Timeline timeline = addTimeline(title);
        changed();
        return timeline;
    }

    private Timeline addTimeline(String title) {
        Timeline timeline = new Timeline(newId(), blankToNull(title) == null ? UNTITLED : title.strip(), null, clock.getAsLong(), null);
        timelines.add(timeline);
        return timeline;
    }

    /**
     * Add an imported timeline at the end, made of frozen entities. It is not made active.
     *
     * @param entities each entity's summary and notes, in order; the entities get new ids.
     * @return the timeline; its entities' folders are for the caller to fill.
     */
    public synchronized Timeline addImported(String title, String notes, JsonObject source, List<JsonObject> entities) {
        Timeline timeline = new Timeline(newId(), blankToNull(title) == null ? UNTITLED : title.strip(), notes,
                clock.getAsLong(), source);
        for (JsonObject entity : entities) {
            timeline.entities.add(new Entity(newId(), entity.getJsonObject("summary"), null, entity.getString("notes")));
        }
        timelines.add(timeline);
        changed();
        return timeline;
    }

    /**
     * Make a timeline the one recorded events go to, or none.
     *
     * @param id the timeline, or null for none.
     */
    public synchronized void setActive(String id) {
        if (id != null) {
            requireTimeline(id);
        }
        activeTimelineId = id;
        changed();
    }

    public synchronized void rename(String id, String title) {
        Timeline timeline = requireTimeline(id);
        timeline.title = blankToNull(title) == null ? UNTITLED : title.strip();
        changed();
    }

    public synchronized void setNotes(String timelineId, String notes) {
        requireTimeline(timelineId).notes = blankToNull(notes);
        changed();
    }

    public synchronized void setEntityNotes(String entityId, String notes) {
        entity(entityId).orElseThrow(() -> new IllegalArgumentException("No entity " + entityId)).notes = blankToNull(notes);
        changed();
    }

    /**
     * Delete a timeline. If it was active, none is until one is chosen, or the next recorded event creates one.
     *
     * @return its entities, whose folders the caller deletes.
     */
    public synchronized List<Entity> delete(String id) {
        Timeline timeline = requireTimeline(id);
        timelines.remove(timeline);
        if (id.equals(activeTimelineId)) {
            activeTimelineId = null;
        }
        changed();
        return List.copyOf(timeline.entities);
    }

    /**
     * Cut a timeline after an entity, like cutting a clip: the entity ends the timeline, and the entities after it move,
     * in order and with their notes, to a new timeline right below it, titled after it. If the cut timeline was active,
     * the new one is, so recorded events keep landing after the latest ones.
     *
     * @return the two halves; {@link Cut#second()} is null if nothing followed the entity, and nothing changed.
     */
    public synchronized Cut cut(String timelineId, String entityId) {
        Timeline first = requireTimeline(timelineId);
        int index = -1;
        for (int i = 0; i < first.entities.size(); i++) {
            if (first.entities.get(i).id.equals(entityId)) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            throw new IllegalArgumentException("Timeline " + timelineId + " has no entity " + entityId);
        }
        if (index == first.entities.size() - 1) {
            return new Cut(first, null);
        }
        Timeline second = new Timeline(newId(), first.title + " (2)", null, clock.getAsLong(), first.source);
        List<Entity> tail = first.entities.subList(index + 1, first.entities.size());
        second.entities.addAll(tail);
        tail.clear();
        timelines.add(timelines.indexOf(first) + 1, second);
        if (first.id.equals(activeTimelineId)) {
            activeTimelineId = second.id;
        }
        changed();
        return new Cut(first, second);
    }

    // ---------------------------------------------------------------- state

    /**
     * @return the whole workspace, which is both what is saved and what the UI reads.
     */
    public synchronized JsonObject toJson() {
        return new JsonObject()
                .put("version", version)
                .put("activeTimelineId", activeTimelineId)
                .put("timelines", new JsonArray(timelines.stream().map(Timeline::toJson).toList()));
    }

    /**
     * @return a workspace as saved by {@link #toJson()}.
     */
    public static Workspace fromJson(JsonObject json) {
        Workspace workspace = new Workspace();
        for (Object o : json.getJsonArray("timelines", new JsonArray())) {
            workspace.timelines.add(Timeline.fromJson((JsonObject) o));
        }
        String active = json.getString("activeTimelineId");
        workspace.activeTimelineId = active != null && workspace.timeline(active).isPresent() ? active : null;
        workspace.version = json.getLong("version", 0L);
        return workspace;
    }

    /**
     * Forget the entities whose recording session is gone, e.g. after a restart: nothing holds their rows any more.
     *
     * @return how many were dropped.
     */
    public synchronized int dropLive() {
        int dropped = 0;
        for (Timeline timeline : timelines) {
            int before = timeline.entities.size();
            timeline.entities.removeIf(Entity::isLive);
            dropped += before - timeline.entities.size();
        }
        if (dropped > 0) {
            changed();
        }
        return dropped;
    }

    /**
     * Forget the entities a session still held when it ended without freezing them, e.g. because it was undeployed.
     *
     * @return how many were dropped.
     */
    public synchronized int dropLive(String sessionId) {
        int dropped = 0;
        for (Timeline timeline : timelines) {
            int before = timeline.entities.size();
            timeline.entities.removeIf(e -> e.live != null && e.live.sessionId().equals(sessionId));
            dropped += before - timeline.entities.size();
        }
        if (dropped > 0) {
            changed();
        }
        return dropped;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
