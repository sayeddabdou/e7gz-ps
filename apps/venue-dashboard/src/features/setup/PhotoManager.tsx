import { useEffect, useState } from "react";
import type { components } from "@e7gz/api-client";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { setupErrorMessage } from "./errorMessages";
import { OwnerPasswordDialog } from "./OwnerPasswordDialog";
import { setupStyles as styles } from "./styles";

type VenueImage = components["schemas"]["VenueImage"];
type UploadAction = { kind: "upload"; file: File; imageId?: string };
type PhotoAction = UploadAction | { kind: "reorder" } | { kind: "remove"; image: VenueImage };
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

export function PhotoManager({ api, uploader = uploadBinary }: { api: ApiClient; uploader?: BinaryUploader }) {
  const t = useMessages();
  const [images, setImages] = useState<VenueImage[]>();
  const [selectedFile, setSelectedFile] = useState<File>();
  const [retryFile, setRetryFile] = useState<File>();
  const [action, setAction] = useState<PhotoAction>();
  const [progress, setProgress] = useState<number>();
  const [dirtyOrder, setDirtyOrder] = useState(false);
  const [error, setError] = useState<string>();
  const [passwordError, setPasswordError] = useState<string>();
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

  const failUpload = (file: File, message: string): boolean => {
    setRetryFile(file);
    setError(message);
    setProgress(undefined);
    return true;
  };

  const run = async (ownerPassword: string): Promise<boolean> => {
    if (!action) return false;
    setPasswordError(undefined);
    setError(undefined);
    try {
      if (action.kind === "upload") {
        let imageId = action.imageId;
        if (!imageId) {
          const start = await api.POST("/api/v1/venue/images", { body: { contentType: action.file.type, sizeBytes: action.file.size } });
          if (!start.data) return failUpload(action.file, setupErrorMessage(apiErrorOf(start.error).code, t));
          imageId = start.data.data.image.id;
          setAction({ ...action, imageId });
          setProgress(0);
          try { await uploader(start.data.data.uploadUrl, action.file, setProgress); }
          catch { return failUpload(action.file, t.setup.errors.network); }
        }
        const confirm = await api.POST("/api/v1/venue/images/{imageId}/confirm", { params: { path: { imageId } }, body: { ownerPassword } });
        if (!confirm.data) {
          setPasswordError(setupErrorMessage(apiErrorOf(confirm.error).code, t));
          return false;
        }
        setSelectedFile(undefined);
        setRetryFile(undefined);
        setProgress(undefined);
      } else if (action.kind === "reorder") {
        const result = await api.PUT("/api/v1/venue/images/order", { body: { imageIds: images?.map((image) => image.id) ?? [], ownerPassword } });
        if (!result.data) { setPasswordError(setupErrorMessage(apiErrorOf(result.error).code, t)); return false; }
      } else {
        const result = await api.POST("/api/v1/venue/images/{imageId}/remove", { params: { path: { imageId: action.image.id } }, body: { ownerPassword } });
        if (!result.response.ok) { setPasswordError(setupErrorMessage(apiErrorOf(result.error).code, t)); return false; }
      }
      setAction(undefined);
      setRefreshVersion((version) => version + 1);
      return true;
    } catch {
      if (action.kind === "upload") return failUpload(action.file, t.setup.errors.network);
      setPasswordError(t.setup.errors.network);
      return false;
    }
  };

  const move = (index: number, by: number) => {
    if (!images) return;
    const target = index + by;
    if (target < 0 || target >= images.length) return;
    const next = [...images];
    [next[index], next[target]] = [next[target]!, next[index]!];
    setImages(next);
    setDirtyOrder(true);
  };

  const actionLabel = action?.kind === "reorder" ? t.setup.photos.saveOrder : action?.kind === "remove" ? t.setup.photos.removeConfirm : t.setup.photos.upload;

  return (
    <section aria-labelledby="photos-title" style={styles.card}>
      <div><h2 id="photos-title" style={styles.cardTitle}>{t.setup.photos.title}</h2><p style={styles.muted}>{t.setup.photos.description}</p></div>
      <label style={styles.field}><span style={styles.label}>{t.setup.photos.choose}</span><input accept="image/jpeg,image/png,image/webp" type="file" onChange={(event) => setSelectedFile(event.target.files?.[0])} /></label>
      <button disabled={!selectedFile} style={styles.primaryButton} type="button" onClick={() => selectedFile && setAction({ kind: "upload", file: selectedFile })}>{t.setup.photos.upload}</button>
      {progress !== undefined ? <div><label>{t.setup.photos.progress}: {progress}%</label><progress max="100" value={progress}>{progress}%</progress></div> : null}
      {error ? <p role="alert" style={styles.error}>{error}</p> : null}
      {retryFile ? <button style={styles.secondaryButton} type="button" onClick={() => setAction({ kind: "upload", file: retryFile })}>{t.setup.photos.retryUpload}</button> : null}
      {images === undefined && !error ? <p role="status">{t.setup.common.loading}</p> : null}
      {images?.length === 0 ? <p>{t.setup.photos.empty}</p> : null}
      {images?.length ? <ol style={styles.list}>{images.map((image, index) => <li key={image.id} style={styles.card}>
        {image.smallUrl ? <img alt={`${t.setup.photos.title} ${index + 1}`} src={image.smallUrl} style={{ borderRadius: styles.card.borderRadius, maxWidth: "12rem", width: "100%" }} /> : <div style={styles.info}>{t.setup.photos.processing}</div>}
        {index === 0 ? <strong>{t.setup.photos.cardPhoto}</strong> : null}
        <div style={styles.actions}><button disabled={index === 0} style={styles.secondaryButton} type="button" onClick={() => move(index, -1)}>{t.setup.common.moveUp}</button><button disabled={index === images.length - 1} style={styles.secondaryButton} type="button" onClick={() => move(index, 1)}>{t.setup.common.moveDown}</button><button style={styles.dangerButton} type="button" onClick={() => setAction({ kind: "remove", image })}>{t.setup.common.remove}</button></div>
      </li>)}</ol> : null}
      {dirtyOrder ? <button style={styles.primaryButton} type="button" onClick={() => setAction({ kind: "reorder" })}>{t.setup.photos.saveOrder}</button> : null}
      {action?.kind === "remove" ? <p style={styles.info}>{t.setup.photos.removeExplanation}</p> : null}
      <OwnerPasswordDialog actionLabel={actionLabel} error={passwordError} open={Boolean(action)} onCancel={() => { setAction(undefined); setPasswordError(undefined); }} onConfirm={run} />
    </section>
  );
}
