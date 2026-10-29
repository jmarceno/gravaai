package dev.gravaai.ui.meeting;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.meeting.MeetingJob;
import dev.gravaai.services.meeting.MeetingJobMode;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.services.meeting.MeetingSettings;
import dev.gravaai.services.meeting.Subscription;

/**
 * Read/write seam between the meetings UI and {@code dev.gravaai.services.meeting.MeetingService}
 * (Lead decision for P1.11b: AppContext injects the real service through
 * {@link ServiceMeetingUiService}; tests use a deterministic fake).
 *
 * <p>Method-for-method port of the IPC channel group in Appendix A / Appendix B so the React view
 * behaviour carries over unchanged.
 */
public interface MeetingUiService {

	MeetingSettings.View settings();

	Result<MeetingSettings.View> updateSettings(UnaryOperator<MeetingSettings> edit);

	Result<MeetingSettings.View> setApiKey(String apiKey);

	Result<MeetingSettings.View> clearApiKey();

	Result<List<MeetingLibraryEntry>> listMeetings();

	Result<Path> importMeeting(Path sourcePath);

	Result<String> renameMeeting(Path mediaPath, String title);

	Result<List<Path>> deleteMeetings(List<Path> mediaPaths);

	Result<Void> revealMeeting(Path mediaPath);

	Result<MeetingService.FilesView> getFiles(Path mediaPath);

	Result<Void> saveFile(Path mediaPath, MeetingService.FileKind kind, String content);

	Result<MeetingJob> runJob(Path mediaPath, MeetingJobMode mode);

	boolean cancelJob(String jobId);

	List<MeetingJob> listJobs();

	Subscription onJobUpdate(Consumer<MeetingJob> listener);

	List<MeetingService.AssetStatus> listAssets();

	CompletableFuture<Result<Void>> downloadAsset(String assetId);

	Result<Void> removeAsset(String assetId);

	Subscription onAssetProgress(Consumer<MeetingService.AssetProgress> listener);

	Optional<Path> takeOpenRequest();

	Subscription onOpenRequested(Runnable listener);
}
