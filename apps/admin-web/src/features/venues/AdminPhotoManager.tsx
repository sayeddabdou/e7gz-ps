import { useEffect, useRef, useState } from "react";
import type { components } from "@e7gz/api-client";
import { errorCodeOf, idempotencyHeaders, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";

type Image = components["schemas"]["AdminVenueImage"];
type Pending = { file: File; stage: "starting" | "uploading" | "confirming" | "failed"; imageId?: string; error?: string; progress?: number };
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

export function AdminPhotoManager({ api, venueId, uploader = uploadBinary }: { api: ApiClient; venueId: string; uploader?: BinaryUploader }) {
  const [images, setImages] = useState<Image[]>([]);
  const [pending, setPending] = useState<Pending>();
  const [error, setError] = useState<string>();
  const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const pollAttempts = useRef(0);
  useEffect(() => { let active = true; void api.GET("/api/v1/admin/venues/{venueId}/images", { params: { path: { venueId } } }).then(({ data }) => { if (active) { setImages(data?.data ?? []); setLoadFailed(!data); setLoading(false); if (data?.data.every((image) => image.smallUrl && image.mediumUrl)) pollAttempts.current = 0; } }).catch(() => { if (active) { setLoadFailed(true); setLoading(false); } }); return () => { active = false; }; }, [api, venueId, version]);
  useEffect(() => { if (!images.some((image) => !image.smallUrl || !image.mediumUrl) || pollAttempts.current >= 20) return undefined; const timer = setTimeout(() => { pollAttempts.current += 1; setVersion((value) => value + 1); }, 3000); return () => clearTimeout(timer); }, [images, version]);

  const upload = async (next: Pending) => {
    setPending({ ...next, stage: next.imageId ? "confirming" : "starting", error: undefined });
    let retry: Pending = { ...next, imageId: undefined, stage: "failed" };
    try {
      let imageId = next.imageId;
      if (!imageId) {
        const start = await api.POST("/api/v1/admin/venues/{venueId}/images", { params: { path: { venueId }, header: idempotencyHeaders() }, body: { contentType: next.file.type, sizeBytes: next.file.size } });
        if (!start.data) throw new Error(adminErrorMessage(errorCodeOf(start.error)));
        imageId = start.data.data.image.id;
        setPending({ ...next, imageId, stage: "uploading", progress: 0 });
        try {
          await uploader(start.data.data.uploadUrl, next.file, (progress) => setPending((current) => current ? { ...current, progress } : current));
        } catch {
          throw new Error(t.networkError);
        }
      }
      retry = { ...next, imageId, stage: "failed" };
      setPending({ ...next, imageId, stage: "confirming" });
      const confirm = await api.POST("/api/v1/admin/venues/{venueId}/images/{imageId}/confirm", { params: { path: { venueId, imageId }, header: idempotencyHeaders() } });
      if (!confirm.data) throw new Error(adminErrorMessage(errorCodeOf(confirm.error)));
      pollAttempts.current = 0; setPending(undefined); setVersion((v) => v + 1);
    } catch (caught) { setPending({ ...retry, error: caught instanceof Error ? caught.message : t.errors.unexpected }); }
  };
  const saveOrder = async (ordered: Image[]) => {
    const previous = images;
    setImages(ordered); setError(undefined);
    try {
      const result = await api.PUT("/api/v1/admin/venues/{venueId}/images/order", { params: { path: { venueId }, header: idempotencyHeaders() }, body: { imageIds: ordered.map((image) => image.id) } });
      if (result.error) { setImages(previous); setError(adminErrorMessage(errorCodeOf(result.error))); }
    } catch { setImages(previous); setError(t.networkError); }
  };
  const move = (index: number, offset: number) => { const ordered = [...images]; const target = index + offset; if (target < 0 || target >= ordered.length) return; [ordered[index], ordered[target]] = [ordered[target]!, ordered[index]!]; void saveOrder(ordered); };
  const remove = async (imageId: string) => { setError(undefined); try { const result = await api.POST("/api/v1/admin/venues/{venueId}/images/{imageId}/remove", { params: { path: { venueId, imageId }, header: idempotencyHeaders() } }); if (result.response.ok) setImages((current) => current.filter((image) => image.id !== imageId)); else setError(adminErrorMessage(errorCodeOf(result.error))); } catch { setError(t.networkError); } };
  return <section className="setup-card"><div className="card-heading"><h3>{t.photos.title}</h3><span>{images.length}</span></div>
    <label className="upload-zone"><span aria-hidden="true">▧</span><strong>{t.photos.add}</strong><small>{t.photos.formats}</small><input aria-label={t.photos.add} type="file" accept="image/jpeg,image/png,image/webp" onChange={(event) => { const file = event.target.files?.[0]; if (file) void upload({ file, stage: "starting" }); }} /></label>
    {pending && <div className={`notice ${pending.stage === "failed" ? "notice--error" : "notice--info"}`} role={pending.stage === "failed" ? "alert" : "status"}><p>{pending.stage === "failed" ? pending.error : pending.stage === "uploading" && pending.progress !== undefined ? t.photos.uploadingProgress(pending.progress) : t.photos[pending.stage]}</p>{pending.stage === "uploading" && pending.progress !== undefined ? <progress aria-label={t.photos.uploadProgress} max="100" value={pending.progress}>{pending.progress}%</progress> : null}{pending.stage === "failed" && <button className="button button--secondary" type="button" onClick={() => void upload(pending)}>{t.photos.retry}</button>}</div>}
    {error && <p className="notice notice--error" role="alert">{error}</p>}
    {loading && <p className="empty-copy" role="status">{t.loading}</p>}
    {loadFailed && <div className="notice notice--error" role="alert">{t.errors.unexpected} <button className="button button--secondary" type="button" onClick={() => { setLoading(true); setLoadFailed(false); setVersion((v) => v + 1); }}>{t.retry}</button></div>}
    {!loading && !loadFailed && !images.length && <p className="empty-copy">{t.photos.empty}</p>}
    <ol className="photo-grid">{images.map((image, index) => <li key={image.id}>{image.mediumUrl ? <img alt={t.photos.photoAlt(index + 1)} src={image.mediumUrl} /> : <span className="photo-processing">{t.photos.processing}</span>}{index === 0 && <strong className="photo-badge">{t.photos.venueCard}</strong>}<div className="photo-actions"><button className="button button--compact button--secondary" type="button" disabled={index === 0} onClick={() => move(index, -1)}>{t.moveUp}</button><button className="button button--compact button--secondary" type="button" disabled={index === images.length - 1} onClick={() => move(index, 1)}>{t.moveDown}</button><button className="button button--compact button--danger" type="button" onClick={() => void remove(image.id)}>{t.photos.hide}</button></div></li>)}</ol>
  </section>;
}
