import { useEffect, useState } from "react";
import type { components } from "@e7gz/api-client";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { setupErrorMessage } from "./errorMessages";
import { setupStyles as styles } from "./styles";

type VenueImage = components["schemas"]["VenueImage"];
/** An upload whose bytes reached storage but whose confirm failed: retrying only confirms it again. */
type PendingUpload = { file: File; imageId?: string };
export type BinaryUploader = (url: string, file: File, onProgress: (percent: number) => void) => Promise<void>;

export const uploadBinary: BinaryUploader = (url, file, onProgress) => new Promise((resolve, reject) => {
  const request = new XMLHttpRequest();
  request.open("PUT", url);
  request.setRequestHeader("Content-Type", file.type);
  request.upload.addEventListener("progress", (event) => {
    if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100));
  });
  request.addEventListener("load", () => request.status >= 200 && request.status < 300 ? resolve() : reject(new Error("upload failed")));
  request.addEventListener("error", () => reject(new Error("upload failed")));
  request.send(file);
});

/** Venue photos (PCR-005). No photo action asks for the Owner Password (PCR-006); every change is audited. */
export function PhotoManager({ api, uploader = uploadBinary }: { api: ApiClient; uploader?: BinaryUploader }) {
  const t = useMessages();
  const [images, setImages] = useState<VenueImage[]>();
  const [selectedFile, setSelectedFile] = useState<File>();
  const [retry, setRetry] = useState<PendingUpload>();
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<number>();
  const [dirtyOrder, setDirtyOrder] = useState(false);
  const [error, setError] = useState<string>();
  const [refreshVersion, setRefreshVersion] = useState(0);

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/venue/images").then(({ data, error: body }) => {
      if (!active) return;
      if (data) { setImages(data.data); setDirtyOrder(false); }
      else setError(setupErrorMessage(apiErrorOf(body).code, t));
    }).catch(() => { if (active) setError(t.setup.errors.network); });
    return () => { active = false; };
  }, [api, refreshVersion, t]);

  const perform = async (work: () => Promise<boolean>) => {
    setBusy(true);
    setError(undefined);
    try {
      if (await work()) setRefreshVersion((version) => version + 1);
    } catch {
      setError(t.setup.errors.network);
    } finally {
      setBusy(false);
    }
  };

  const upload = (pending: PendingUpload) => perform(async () => {
    const failUpload = (message: string, imageId?: string) => {
      setRetry({ file: pending.file, ...(imageId ? { imageId } : {}) });
      setError(message);
      setProgress(undefined);
      return false;
    };
    let imageId = pending.imageId;
    if (!imageId) {
      const start = await api.POST("/api/v1/venue/images", { body: { contentType: pending.file.type, sizeBytes: pending.file.size } });
      if (!start.data) return failUpload(setupErrorMessage(apiErrorOf(start.error).code, t));
      imageId = start.data.data.image.id;
      setProgress(0);
      try { await uploader(start.data.data.uploadUrl, pending.file, setProgress); }
      catch { return failUpload(t.setup.errors.network); }
    }
    try {
      const confirm = await api.POST("/api/v1/venue/images/{imageId}/confirm", { params: { path: { imageId } } });
      if (!confirm.data) return failUpload(setupErrorMessage(apiErrorOf(confirm.error).code, t), imageId);
    } catch {
      return failUpload(t.setup.errors.network, imageId);
    }
    setSelectedFile(undefined);
    setRetry(undefined);
    setProgress(undefined);
    return true;
  });

  const saveOrder = () => perform(async () => {
    const result = await api.PUT("/api/v1/venue/images/order", { body: { imageIds: images?.map((image) => image.id) ?? [] } });
    if (!result.data) { setError(setupErrorMessage(apiErrorOf(result.error).code, t)); return false; }
    return true;
  });

  const remove = (image: VenueImage) => perform(async () => {
    const result = await api.POST("/api/v1/venue/images/{imageId}/remove", { params: { path: { imageId: image.id } } });
    if (!result.response.ok) { setError(setupErrorMessage(apiErrorOf(result.error).code, t)); return false; }
    return true;
  });

  const move = (index: number, by: number) => {
    if (!images) return;
    const target = index + by;
    if (target < 0 || target >= images.length) return;
    const next = [...images];
    [next[index], next[target]] = [next[target]!, next[index]!];
    setImages(next);
    setDirtyOrder(true);
  };

  return (
    <section aria-labelledby="photos-title" style={styles.card}>
      <div><h2 id="photos-title" style={styles.cardTitle}>{t.setup.photos.title}</h2><p style={styles.muted}>{t.setup.photos.description}</p></div>
      <label style={styles.field}><span style={styles.label}>{t.setup.photos.choose}</span><input accept="image/jpeg,image/png,image/webp" type="file" onChange={(event) => setSelectedFile(event.target.files?.[0])} /></label>
      <button disabled={!selectedFile || busy} style={styles.primaryButton} type="button" onClick={() => selectedFile && void upload({ file: selectedFile })}>{busy && progress !== undefined ? t.setup.photos.uploading : t.setup.photos.upload}</button>
      {progress !== undefined ? <div><label>{t.setup.photos.progress}: {progress}%</label><progress max="100" value={progress}>{progress}%</progress></div> : null}
      {error ? <p role="alert" style={styles.error}>{error}</p> : null}
      {retry ? <button disabled={busy} style={styles.secondaryButton} type="button" onClick={() => void upload(retry)}>{t.setup.photos.retryUpload}</button> : null}
      {images === undefined && !error ? <p role="status">{t.setup.common.loading}</p> : null}
      {images?.length === 0 ? <p>{t.setup.photos.empty}</p> : null}
      {images?.length ? <ol style={styles.list}>{images.map((image, index) => <li key={image.id} style={styles.card}>
        {image.smallUrl ? <img alt={`${t.setup.photos.title} ${index + 1}`} src={image.smallUrl} style={{ borderRadius: styles.card.borderRadius, maxWidth: "12rem", width: "100%" }} /> : <div style={styles.info}>{t.setup.photos.processing}</div>}
        {index === 0 ? <strong>{t.setup.photos.cardPhoto}</strong> : null}
        <div style={styles.actions}><button disabled={index === 0 || busy} style={styles.secondaryButton} type="button" onClick={() => move(index, -1)}>{t.setup.common.moveUp}</button><button disabled={index === images.length - 1 || busy} style={styles.secondaryButton} type="button" onClick={() => move(index, 1)}>{t.setup.common.moveDown}</button><button disabled={busy} style={styles.dangerButton} type="button" onClick={() => void remove(image)}>{t.setup.common.remove}</button></div>
      </li>)}</ol> : null}
      {images?.length ? <p style={styles.info}>{t.setup.photos.removeExplanation}</p> : null}
      {dirtyOrder ? <button disabled={busy} style={styles.primaryButton} type="button" onClick={() => void saveOrder()}>{t.setup.photos.saveOrder}</button> : null}
    </section>
  );
}
