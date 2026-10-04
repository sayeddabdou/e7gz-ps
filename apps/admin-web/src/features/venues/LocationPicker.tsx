import { useEffect, useRef } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import type { Coordinates } from "./location";

export function LocationPicker({ value, onChange }: { value: Coordinates; onChange: (point: Coordinates) => void }) {
  const container = useRef<HTMLDivElement>(null);
  const callback = useRef(onChange);
  useEffect(() => { callback.current = onChange; }, [onChange]);
  useEffect(() => {
    if (!container.current) return undefined;
    const map = L.map(container.current).setView([value.lat, value.lng], 14);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { attribution: "© OpenStreetMap contributors" }).addTo(map);
    const marker = L.marker([value.lat, value.lng], { draggable: true }).addTo(map);
    map.on("click", (event: L.LeafletMouseEvent) => { marker.setLatLng(event.latlng); callback.current({ lat: event.latlng.lat, lng: event.latlng.lng }); });
    marker.on("dragend", () => { const point = marker.getLatLng(); callback.current({ lat: point.lat, lng: point.lng }); });
    return () => { map.remove(); };
  }, [value.lat, value.lng]);
  return <div aria-label="Venue location map" ref={container} style={{ blockSize: "18rem", inlineSize: "100%" }} />;
}
