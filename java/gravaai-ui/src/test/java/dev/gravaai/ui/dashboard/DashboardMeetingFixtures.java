package dev.gravaai.ui.dashboard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.meeting.MeetingJob;
import dev.gravaai.services.meeting.MeetingJobMode;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.services.meeting.MeetingSettings;
import dev.gravaai.services.meeting.Subscription;
import dev.gravaai.ui.meeting.MeetingUiService;

/**
 * Deterministic in-memory {@link MeetingUiService} for the dashboard meetings tests (no real
 * meeting service, no files). Only the calls the dashboard makes are meaningful; the rest fail
 * loudly so an accidental new dependency shows up as a test failure.
 */
final class DashboardMeetingFixtures {

	static final Path DESIGN_REVIEW = Path.of("/tmp/meetings/design-review.mp4");
	static final Path WEEKLY_SYNC = Path.of("/tmp/meetings/weekly-sync.m4a");
	static final Path RETRO = Path.of("/tmp/meetings/retro.m4a");

	static final long NOW = 1_800_000_000_000L;
	static final long HOUR = 3_600_000L;
	static final long DAY = 86_400_000L;

	private DashboardMeetingFixtures() {
	}

	/** Video meeting with transcript and notes (status "running"). */
	static MeetingLibraryEntry designReview(long createdAt) {
		return new MeetingLibraryEntry(DESIGN_REVIEW, "Design review", createdAt, 750_000L,
				true, true, true, MeetingLibraryEntry.Status.RUNNING, null);
	}

	/** Audio meeting with a transcript but no notes (idle, so no badge). */
	static MeetingLibraryEntry weeklySync(long createdAt) {
		return new MeetingLibraryEntry(WEEKLY_SYNC, "Weekly sync", createdAt, 1_800_000L,
				false, true, false, MeetingLibraryEntry.Status.IDLE, null);
	}

	/** Failed audio meeting without transcript or duration. */
	static MeetingLibraryEntry retro(long createdAt) {
		return new MeetingLibraryEntry(RETRO, "Retro notes", createdAt, null,
				false, false, false, MeetingLibraryEntry.Status.FAILED, "Whisper crashed");
	}

	/** The three fixture meetings, newest first. */
	static List<MeetingLibraryEntry> meetings() {
		return List.of(designReview(NOW - 2 * HOUR), retro(NOW - 10 * DAY),
				weeklySync(NOW - 26 * HOUR));
	}

	static List<MeetingLibraryEntry> sortedMeetings() {
		List<MeetingLibraryEntry> entries = new ArrayList<>(meetings());
		entries.sort(java.util.Comparator.comparingLong(MeetingLibraryEntry::createdAt).reversed());
		return entries;
	}

	static final class FakeMeetingUiService implements MeetingUiService {

		final List<MeetingLibraryEntry> meetings = new ArrayList<>(meetings());
		final List<Consumer<MeetingJob>> jobListeners = new ArrayList<>();
		final List<String> renames = new ArrayList<>();
		final List<List<Path>> deleteRequests = new ArrayList<>();
		final Set<String> undeletable = new LinkedHashSet<>();
		String listError;

		@Override
		public Result<List<MeetingLibraryEntry>> listMeetings() {
			return listError != null ? Result.err(listError) : Result.ok(List.copyOf(meetings));
		}

		@Override
		public Result<String> renameMeeting(Path mediaPath, String title) {
			renames.add(mediaPath + "=" + title);
			for (int index = 0; index < meetings.size(); index++) {
				MeetingLibraryEntry entry = meetings.get(index);
				if (entry.mediaPath().equals(mediaPath)) {
					meetings.set(index, new MeetingLibraryEntry(entry.mediaPath(), title,
							entry.createdAt(), entry.durationMs(), entry.hasVideo(),
							entry.hasTranscript(), entry.hasNotes(), entry.status(), entry.error()));
				}
			}
			return Result.ok(title);
		}

		@Override
		public Result<List<Path>> deleteMeetings(List<Path> mediaPaths) {
			deleteRequests.add(List.copyOf(mediaPaths));
			List<Path> deleted = new ArrayList<>();
			for (Path mediaPath : mediaPaths) {
				if (undeletable.contains(mediaPath.toString())) {
					continue;
				}
				meetings.removeIf(entry -> entry.mediaPath().equals(mediaPath));
				deleted.add(mediaPath);
			}
			return Result.ok(List.copyOf(deleted));
		}

		@Override
		public Subscription onJobUpdate(Consumer<MeetingJob> listener) {
			jobListeners.add(listener);
			return () -> jobListeners.remove(listener);
		}

		@Override
		public MeetingSettings.View settings() {
			throw new UnsupportedOperationException("settings not used by the dashboard");
		}

		@Override
		public Result<MeetingSettings.View> setSettings(Map<String, Object> patch) {
			throw new UnsupportedOperationException("setSettings not used by the dashboard");
		}

		@Override
		public Result<Path> importMeeting(Path sourcePath) {
			throw new UnsupportedOperationException("importMeeting not used by the dashboard");
		}

		@Override
		public Result<Void> revealMeeting(Path mediaPath) {
			throw new UnsupportedOperationException("revealMeeting not used by the dashboard");
		}

		@Override
		public Result<MeetingService.FilesView> getFiles(Path mediaPath) {
			throw new UnsupportedOperationException("getFiles not used by the dashboard");
		}

		@Override
		public Result<Void> saveFile(Path mediaPath, MeetingService.FileKind kind, String content) {
			throw new UnsupportedOperationException("saveFile not used by the dashboard");
		}

		@Override
		public Result<MeetingJob> runJob(Path mediaPath, MeetingJobMode mode) {
			throw new UnsupportedOperationException("runJob not used by the dashboard");
		}

		@Override
		public boolean cancelJob(String jobId) {
			throw new UnsupportedOperationException("cancelJob not used by the dashboard");
		}

		@Override
		public List<MeetingJob> listJobs() {
			throw new UnsupportedOperationException("listJobs not used by the dashboard");
		}

		@Override
		public List<MeetingService.AssetStatus> listAssets() {
			throw new UnsupportedOperationException("listAssets not used by the dashboard");
		}

		@Override
		public CompletableFuture<Result<Void>> downloadAsset(String assetId) {
			throw new UnsupportedOperationException("downloadAsset not used by the dashboard");
		}

		@Override
		public Result<Void> removeAsset(String assetId) {
			throw new UnsupportedOperationException("removeAsset not used by the dashboard");
		}

		@Override
		public Subscription onAssetProgress(Consumer<MeetingService.AssetProgress> listener) {
			throw new UnsupportedOperationException("onAssetProgress not used by the dashboard");
		}

		@Override
		public Optional<Path> takeOpenRequest() {
			throw new UnsupportedOperationException("takeOpenRequest not used by the dashboard");
		}

		@Override
		public Subscription onOpenRequested(Runnable listener) {
			throw new UnsupportedOperationException("onOpenRequested not used by the dashboard");
		}
	}
}
