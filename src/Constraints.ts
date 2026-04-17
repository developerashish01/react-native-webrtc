
export type MediaTrackConstraints = {
    width?: ConstrainNumber;
    height?: ConstrainNumber;
    frameRate?: ConstrainNumber;
    facingMode?: ConstrainString;
    deviceId?: ConstrainString;
    groupId?: ConstrainString;
    source?: ConstrainString;
    deepARLicenseKey?: ConstrainString;
    deepAR?: {
        licenseKey?: string;
        lensFacing?: 'front' | 'back' | 'user' | 'environment';
        effectPath?: string;
    };
}

type ConstrainNumber = number | {
    exact?: number,
    ideal?: number,
    max?: number,
    min?: number,
}

type ConstrainString = string | {
    exact?: string,
    ideal?: string,
}