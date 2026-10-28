package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code src/components/video-editor/editorHistory.test.ts}. */
class EditorHistoryTest {

	private static EditorHistory.Snapshot createSnapshot(String id) {
		return new EditorHistory.Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				id, id != null ? "clip-" + id : null, null, null);
	}

	@Test
	@DisplayName("initializes without adding undo history")
	void initializesWithoutHistory() {
		EditorHistory.Stack stack = EditorHistory.createStack();
		EditorHistory.Snapshot snapshot = createSnapshot("first");

		assertEquals(EditorHistory.RecordResult.INITIALIZED, EditorHistory.record(stack, snapshot));

		assertEquals(snapshot, stack.current());
		assertEquals(List.of(), stack.past());
		assertEquals(List.of(), stack.future());
	}

	@Test
	@DisplayName("does not record unchanged snapshots")
	void doesNotRecordUnchangedSnapshots() {
		EditorHistory.Stack stack = EditorHistory.createStack();
		EditorHistory.record(stack, createSnapshot("same"));

		assertEquals(EditorHistory.RecordResult.UNCHANGED,
				EditorHistory.record(stack, createSnapshot("same")));

		assertEquals(List.of(), stack.past());
		assertEquals(List.of(), stack.future());
	}

	@Test
	@DisplayName("records changes and clears redo history")
	void recordsChangesAndClearsRedo() {
		EditorHistory.Stack stack = EditorHistory.createStack();
		EditorHistory.record(stack, createSnapshot("first"));
		EditorHistory.record(stack, createSnapshot("second"));
		stack.future().add(createSnapshot("future"));

		assertEquals(EditorHistory.RecordResult.RECORDED,
				EditorHistory.record(stack, createSnapshot("third")));

		assertEquals(List.of("first", "second"),
				stack.past().stream().map(EditorHistory.Snapshot::selectedZoomId).toList());
		assertEquals("third", stack.current().selectedZoomId());
		assertEquals(List.of(), stack.future());
	}

	@Test
	@DisplayName("moves snapshots through undo and redo stacks")
	void movesSnapshotsThroughUndoAndRedo() {
		EditorHistory.Stack stack = EditorHistory.createStack();
		EditorHistory.Snapshot first = createSnapshot("first");
		EditorHistory.Snapshot second = createSnapshot("second");
		EditorHistory.record(stack, first);
		EditorHistory.record(stack, second);

		assertEquals("first", EditorHistory.undo(stack, second).selectedZoomId());
		assertEquals("first", stack.current().selectedZoomId());
		assertEquals(List.of("second"),
				stack.future().stream().map(EditorHistory.Snapshot::selectedZoomId).toList());

		assertEquals("second", EditorHistory.redo(stack, first).selectedZoomId());
		assertEquals("second", stack.current().selectedZoomId());
		assertEquals(List.of("first"),
				stack.past().stream().map(EditorHistory.Snapshot::selectedZoomId).toList());
	}

	@Test
	@DisplayName("updates the current snapshot while applying history without recording a new entry")
	void appliesHistoryWithoutRecording() {
		EditorHistory.Stack stack = EditorHistory.createStack();
		EditorHistory.record(stack, createSnapshot("first"));

		assertEquals(EditorHistory.RecordResult.APPLIED,
				EditorHistory.record(stack, createSnapshot("applied"), EditorHistory.RecordOptions.applyHistory()));

		assertEquals("applied", stack.current().selectedZoomId());
		assertEquals(List.of(), stack.past());
	}

	@Test
	@DisplayName("caps the past stack at the configured history depth")
	void capsPastStack() {
		EditorHistory.Stack stack = EditorHistory.createStack();
		EditorHistory.record(stack, createSnapshot("0"));
		EditorHistory.record(stack, createSnapshot("1"), EditorHistory.RecordOptions.withMaxEntries(2));
		EditorHistory.record(stack, createSnapshot("2"), EditorHistory.RecordOptions.withMaxEntries(2));
		EditorHistory.record(stack, createSnapshot("3"), EditorHistory.RecordOptions.withMaxEntries(2));

		assertEquals(List.of("1", "2"),
				stack.past().stream().map(EditorHistory.Snapshot::selectedZoomId).toList());
	}

	@Test
	@DisplayName("resets all history stacks")
	void resetsAllHistoryStacks() {
		EditorHistory.Stack stack = EditorHistory.createStack();
		EditorHistory.record(stack, createSnapshot("first"));
		EditorHistory.record(stack, createSnapshot("second"));
		EditorHistory.undo(stack, createSnapshot("second"));

		EditorHistory.reset(stack);

		EditorHistory.Stack empty = EditorHistory.createStack();
		assertEquals(empty.past(), stack.past());
		assertEquals(empty.pastLabels(), stack.pastLabels());
		assertEquals(empty.current(), stack.current());
		assertEquals(empty.future(), stack.future());
	}

	@Test
	@DisplayName("clones snapshots before storing them")
	void clonesSnapshots() {
		EditorHistory.Snapshot snapshot = createSnapshot("first");
		EditorHistory.Snapshot cloned = EditorHistory.cloneSnapshot(snapshot);

		assertNotSame(snapshot, cloned);
		assertTrue(EditorHistory.areSnapshotsEqual(snapshot, createSnapshot("first")));
		assertEquals(snapshot, cloned);
	}

	@Test
	@DisplayName("labels the change Ctrl+Z would undo")
	void reportsUndoLabel() {
		EditorHistory.Stack stack = EditorHistory.createStack();
		EditorHistory.record(stack, createSnapshot("first"));

		assertNull(EditorHistory.peekUndoLabel(stack));
		EditorHistory.record(stack, createSnapshot("second"),
				new EditorHistory.RecordOptions(null, null, "AI: cut 4 ranges"));
		assertEquals("AI: cut 4 ranges", EditorHistory.peekUndoLabel(stack));

		EditorHistory.undo(stack, createSnapshot("second"));
		assertNull(EditorHistory.peekUndoLabel(stack));
	}
}
