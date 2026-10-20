export const DEFAULT_MAP_VIEW={lat:-19.932,lon:-44.054,zoom:14}

/** Enquadramento inclui bases cadastradas mesmo sem ID CAD ou ocorrências recebidas. */
export function mapPoints(...groups:ReadonlyArray<ReadonlyArray<{lat:number|null;lon:number|null}>>):[number,number][]{
 const points:[number,number][]=[]
 for(const group of groups)for(const point of group){
  if(typeof point.lat==='number'&&typeof point.lon==='number'&&Number.isFinite(point.lat)&&Number.isFinite(point.lon)&&Math.abs(point.lat)<=90&&Math.abs(point.lon)<=180)points.push([point.lat,point.lon])
 }
 return points
}
