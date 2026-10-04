import { useEffect, useRef } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { messages as t } from "../../i18n/messages";
import type { Coordinates } from "./location";

export function LocationPicker({ value, onChange }: { value: Coordinates; onChange: (point: Coordinates) => void }) {
  const container = useRef<HTMLDivElement>(null);
  const callback = useRef(onChange);
  const initialValue = useRef(value);
  const mapRef = useRef<L.Map | null>(null);
  const markerRef = useRef<L.Marker | null>(null);
  useEffect(() => { callback.current = onChange; }, [onChange]);
  useEffect(() => {
    if (!container.current) return undefined;
    const initial = initialValue.current;
    const map = L.map(container.current).setView([initial.lat, initial.lng], 14);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { attribution: "© OpenStreetMap contributors" }).addTo(map);
    const marker = L.marker([initial.lat, initial.lng], { draggable: true }).addTo(map);
    mapRef.current = map;
    markerRef.current = marker;
    map.on("click", (event: L.LeafletMouseEvent) => { marker.setLatLng(event.latlng); callback.current({ lat: event.latlng.lat, lng: event.latlng.lng }); });
    marker.on("dragend", () => { const point = marker.getLatLng(); callback.current({ lat: point.lat, lng: point.lng }); });
    return () => { markerRef.current = null; mapRef.current = null; map.remove(); };
  }, []);
  useEffect(() => {
    const point = L.latLng(value.lat, value.lng);
    markerRef.current?.setLatLng(point);
    mapRef.current?.panTo(point);
  }, [value.lat, value.lng]);
  return <div aria-label={t.venues.locationMap} ref={container} style={{ blockSize: "18rem", inlineSize: "100%" }} />;
}
