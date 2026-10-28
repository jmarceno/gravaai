package dev.gravaai.ui.meeting;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.meeting.MeetingJob;
import dev.gravaai.services.meeting.MeetingJobMode;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.services.meeting.MeetingSettings;
import dev.gravaai.services.meeting.Subscription;

/** AppContext adapter: exposes {@link MeetingService} to the meetings UI. */
public final class ServiceMeetingUiService implements MeetingUiService {

	private final MeetingService service;

	public ServiceMeetingUiService(MeetingService service) {
		this.service = Objects.requireNonNull(service, "service");
	}

	@Override
	public MeetingSettings.View settings() {
		return service.settings();
	}

	@Override
	public Result<MeetingSettings.View> setSettings(Map<String, Object> patch) {
		return service.setSettings(patch);
	}

	@Override
	public Result<List<MeetingLibraryEntry>> listMeetings() {
		return service.listMeetings();
	}

	@Override
	public Result<Path> importMeeting(Path sourcePath) {
		return service.importMeeting(sourcePath);
	}

	@Override
	public Result<String> renameMeeting(Path mediaPath, String title) {
		return service.renameMeeting(mediaPath, title);
	}

	@Override
	public Result<List<Path>> deleteMeetings(List<Path> mediaPaths) {
		return service.deleteMeetings(mediaPaths);
	}

	@Override
	public Result<Void> revealMeeting(Path mediaPath) {
		return service.revealMeeting(mediaPath);
	}

	@Override
	public Result<MeetingService.FilesView> getFiles(Path mediaPath) {
		return service.getFiles(mediaPath);
	}

	@Override
	public Result<Void> saveFile(Path mediaPath, MeetingService.FileKind kind, String content) {
		return service.saveFile(mediaPath, kind, content);
	}

	@Override
	public Result<MeetingJob> runJob(Path mediaPath, MeetingJobMode mode) {
		return service.runJob(mediaPath, mode);
	}

	@Override
	public boolean cancelJob(String jobId) {
		return service.cancelJob(jobId);
	}

	@Override
	public List<MeetingJob> listJobs() {
		return service.listJobs();
	}

	@Override
	public Subscription onJobUpdate(Consumer<MeetingJob> listener) {
		return service.onJobUpdate(listener);
	}

	@Override
	public List<MeetingService.AssetStatus> listAssets() {
		return service.listAssets();
	}

	@Override
	public CompletableFuture<Result<Void>> downloadAsset(String assetId) {
		return service.downloadAsset(assetId);
	}

	@Override
	public Result<Void> removeAsset(String assetId) {
		return service.removeAsset(assetId);
	}

	@Override
	public Subscription onAssetProgress(Consumer<MeetingService.AssetProgress> listener) {
		return service.onAssetProgress(listener);
	}

	@Override
	public Optional<Path> takeOpenRequest() {
		return service.takeOpenRequest();
	}

	@Override
	public Subscription onOpenRequested(Runnable listener) {
		return service.onOpenRequested(listener);
	}
}
