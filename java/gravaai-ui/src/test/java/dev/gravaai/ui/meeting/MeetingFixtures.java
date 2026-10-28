package dev.gravaai.ui.meeting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.meeting.MeetingJob;
import dev.gravaai.services.meeting.MeetingJobMode;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.meeting.MeetingMeta;
import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.services.meeting.MeetingSettings;
import dev.gravaai.services.meeting.MeetingServiceKind;
import dev.gravaai.services.meeting.Subscription;

/**
 * Deterministic in-memory {@link MeetingUiService} for the TestFX snapshots and behaviour tests
 * (no real meeting service, no media decode).
 */
final class MeetingFixtures {

	static final Path VIDEO_MEETING = Path.of("/tmp/meetings/design-review.mp4");
	static final Path AUDIO_MEETING = Path.of("/tmp/meetings/weekly-sync.m4a");

	static final String TRANSCRIPT = """
			# Transcript

			[00:00:12] Welcome everyone, today we review the dashboard design.

			- [x] Open the figma file
			- [ ] Check the spacing
			  - nested detail with **bold** and italic

			[00:01:05] The timeline needs the new indicators.
			""";

	static final String NOTES = """
			# Design review notes

			## Decisions

			- Keep the **compact** cards
			- Add the *hover* preview later
			- Use code like sample for placeholders

			## Next steps

			[00:01:05] Finish the timeline indicators.
			""";

	private MeetingFixtures() {
	}

	static MeetingUiService service() {
		return new FakeMeetingUiService();
	}

	/** A service with one video meeting (running job) and one audio-only meeting. */
	static final class FakeMeetingUiService implements MeetingUiService {

		private final List<MeetingLibraryEntry> meetings = new ArrayList<>();
		private final List<MeetingJob> jobs = new ArrayList<>();
		private final List<Consumer<MeetingJob>> jobListeners = new ArrayList<>();
		private final List<Consumer<MeetingService.AssetProgress>> assetListeners = new ArrayList<>();
		private final List<Runnable> openListeners = new ArrayList<>();
		private final Map<Path, String> transcripts = new LinkedHashMap<>();
		private final Map<Path, String> notes = new LinkedHashMap<>();
		private final Map<String, Integer> assetProgress = new LinkedHashMap<>();
		private final List<Map<String, Object>> savedSettings = new ArrayList<>();
		private final List<String> downloadedAssets = new ArrayList<>();
		private final List<String> removedAssets = new ArrayList<>();
		private MeetingJob activeJob;
		private boolean released;

		FakeMeetingUiService() {
			transcripts.put(VIDEO_MEETING, TRANSCRIPT);
			notes.put(VIDEO_MEETING, NOTES);
			meetings.add(new MeetingLibraryEntry(VIDEO_MEETING, "Design review", 1_800_000_000_000L,
					3_661_000L, true, true, true, MeetingLibraryEntry.Status.RUNNING, null));
			meetings.add(new MeetingLibraryEntry(AUDIO_MEETING, "Weekly sync", 1_799_900_000_000L,
					305_000L, false, true, false, MeetingLibraryEntry.Status.IDLE, null));
			activeJob = new MeetingJob("job-1", VIDEO_MEETING, MeetingJobMode.FULL,
					MeetingJob.Status.RUNNING, MeetingJob.Stage.SUMMARIZE, "Writing the summary…", 40,
					null, "2026-10-27T19:00:00Z", null);
			jobs.add(activeJob);
		}

		MeetingJob activeJob() {
			return activeJob;
		}

		List<Map<String, Object>> savedSettings() {
			return savedSettings;
		}

		List<String> downloadedAssets() {
			return downloadedAssets;
		}

		List<String> removedAssets() {
			return removedAssets;
		}

		List<MeetingLibraryEntry> meetings() {
			return meetings;
		}

		void completeJob() {
			activeJob = new MeetingJob("job-1", VIDEO_MEETING, MeetingJobMode.FULL,
					MeetingJob.Status.DONE, MeetingJob.Stage.TITLE, "Done", 100, null,
					"2026-10-27T19:00:00Z", "2026-10-27T19:01:00Z");
			jobs.add(activeJob);
			for (Consumer<MeetingJob> listener : jobListeners) {
				listener.accept(activeJob);
			}
		}

		@Override
		public MeetingSettings.View settings() {
			return new MeetingSettings.View(MeetingSettings.DEFAULT, true);
		}

		@Override
		public Result<MeetingSettings.View> setSettings(Map<String, Object> patch) {
			savedSettings.add(patch);
			return Result.ok(settings());
		}

		@Override
		public Result<List<MeetingLibraryEntry>> listMeetings() {
			return Result.ok(List.copyOf(meetings));
		}

		@Override
		public Result<Path> importMeeting(Path sourcePath) {
			return Result.ok(sourcePath);
		}

		@Override
		public Result<String> renameMeeting(Path mediaPath, String title) {
			for (int index = 0; index < meetings.size(); index++) {
				MeetingLibraryEntry entry = meetings.get(index);
				if (entry.mediaPath().equals(mediaPath)) {
					meetings.set(index, new MeetingLibraryEntry(entry.mediaPath(), title,
							entry.createdAt(), entry.durationMs(), entry.hasVideo(),
							entry.hasTranscript(), entry.hasNotes(), entry.status(), entry.error()));
					break;
				}
			}
			return Result.ok(title);
		}

		@Override
		public Result<List<Path>> deleteMeetings(List<Path> mediaPaths) {
			List<Path> deleted = new ArrayList<>();
			for (Path path : mediaPaths) {
				meetings.removeIf(entry -> entry.mediaPath().equals(path));
				deleted.add(path);
			}
			return Result.ok(deleted);
		}

		@Override
		public Result<Void> revealMeeting(Path mediaPath) {
			return Result.ok(null);
		}

		@Override
		public Result<MeetingService.FilesView> getFiles(Path mediaPath) {
			String transcript = transcripts.get(mediaPath);
			String body = notes.get(mediaPath);
			if (transcript == null && body == null) {
				return Result.err("Unknown meeting");
			}
			MeetingMeta meta = new MeetingMeta("meeting", "2026-10-27T12:00:00Z", true,
					"english", 3_661_000L, "Design review", "auto", null, null, null, null, null);
			if (mediaPath.equals(AUDIO_MEETING)) {
				meta = new MeetingMeta("meeting", "2026-10-26T09:30:00Z", false, "english",
						305_000L, "Weekly sync", "auto", null, null, null, null, null);
			}
			return Result.ok(new MeetingService.FilesView(null, transcript, body, meta, false));
		}

		@Override
		public Result<Void> saveFile(Path mediaPath, MeetingService.FileKind kind, String content) {
			if (kind == MeetingService.FileKind.TRANSCRIPT) {
				transcripts.put(mediaPath, content);
			} else {
				notes.put(mediaPath, content);
			}
			return Result.ok(null);
		}

		@Override
		public Result<MeetingJob> runJob(Path mediaPath, MeetingJobMode mode) {
			activeJob = new MeetingJob("job-" + (jobs.size() + 1), mediaPath, mode,
					MeetingJob.Status.QUEUED, MeetingJob.Stage.TRANSCRIBE, "Waiting to start", null,
					null, "2026-10-27T19:00:00Z", null);
			jobs.add(activeJob);
			return Result.ok(activeJob);
		}

		@Override
		public boolean cancelJob(String jobId) {
			return true;
		}

		@Override
		public List<MeetingJob> listJobs() {
			return List.copyOf(jobs);
		}

		@Override
		public Subscription onJobUpdate(Consumer<MeetingJob> listener) {
			jobListeners.add(listener);
			return () -> jobListeners.remove(listener);
		}

		@Override
		public List<MeetingService.AssetStatus> listAssets() {
			return List.of(
					new MeetingService.AssetStatus("llama-runtime", "llama.cpp b11829 (Vulkan)",
							34_989_558L, false),
					new MeetingService.AssetStatus("large-v3-turbo-q5_0", "Whisper large-v3-turbo (q5)",
							574_041_195L, true),
					new MeetingService.AssetStatus("whisper-vad", "Silero voice detection", 885_098L,
							true),
					new MeetingService.AssetStatus("qwen3.5-2b", "Qwen3.5 2B (Q4_K_M)",
							1_280_835_840L, true));
		}

		@Override
		public CompletableFuture<Result<Void>> downloadAsset(String assetId) {
			downloadedAssets.add(assetId);
			assetProgress.put(assetId, 0);
			for (Consumer<MeetingService.AssetProgress> listener : assetListeners) {
				listener.accept(new MeetingService.AssetProgress(assetId, 40, false));
			}
			return CompletableFuture.completedFuture(Result.ok(null));
		}

		@Override
		public Result<Void> removeAsset(String assetId) {
			removedAssets.add(assetId);
			return Result.ok(null);
		}

		@Override
		public Subscription onAssetProgress(Consumer<MeetingService.AssetProgress> listener) {
			assetListeners.add(listener);
			return () -> assetListeners.remove(listener);
		}

		@Override
		public Optional<Path> takeOpenRequest() {
			return Optional.empty();
		}

		@Override
		public Subscription onOpenRequested(Runnable listener) {
			openListeners.add(listener);
			return () -> openListeners.remove(listener);
		}
	}
}
