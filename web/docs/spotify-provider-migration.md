# Spotify integration plan for BitChord Android

## Native implementation boundary

The native data layer has a dedicated com.music.bitchord.data.remote.spotify package for Spotify OAuth/PKCE and Web API calls. It is intentionally separate from the existing MusicSource / SourceResolver audio-stream contract.

The Spotify Web API can provide track metadata, playlist metadata, current playback state, and playback commands. It does not return audio stream URLs for BitChord's own decoder. The official Android App Remote SDK controls the installed Spotify app; it does not hand Spotify audio to BitChord's ExoPlayer/DSP/crossfade engine. Therefore, a compliant migration must keep these as separate playback destinations rather than claiming Spotify can be a normal MusicSource.

## Implemented in this commit

- SpotifyAuthRepository.kt: Authorization Code with PKCE, random verifier and state, S256 challenge, callback state verification, authorization-code exchange, refresh-token rotation handling, expiry skew, encrypted preference storage, and sign-out.
- SpotifyWebApi.kt: official GET /v1/search, GET /v1/me/playlists, current playback state, and supported playback control endpoints. Track and playlist DTOs do not pretend to contain stream URLs.
- app/build.gradle.kts: reads the public Spotify client ID and exact redirect URI from local configuration without hardcoding credentials.

## Configure local development

Add to local.properties (do not commit this file):

    SPOTIFY_CLIENT_ID=your_spotify_client_id
    SPOTIFY_REDIRECT_URI=https://your-verified-domain.example/spotify/callback

The redirect URI must be registered exactly in Spotify Developer Dashboard. Spotify's current redirect URI rules require HTTPS except for loopback IP literals; do not use localhost. The Activity/browser callback integration must deliver the returned URI to SpotifyAuthRepository.completeAuthorization(uri).

The repository deliberately does not invent a production redirect domain or add a callback intent filter for a URI that has not been registered.

## App Remote SDK follow-up

Spotify distributes the App Remote Android SDK as an SDK bundle containing an AAR that must be imported into the Android project; this repository does not currently contain that AAR/module. Adding direct SpotifyAppRemote imports without the dependency would break compilation. Once the official AAR is supplied, a small Android-only adapter can connect/disconnect and observe PlayerState, while this Web API client remains responsible for search and playlists. The adapter must not try to obtain audio bytes from Spotify or merge Spotify playback into the existing local stream pipeline.

## Policy and distribution checks

Spotify API access, playback eligibility, Premium requirements, development-mode user limits, and platform policies can change. Verify current dashboard and policy requirements before release. Spotify artwork and metadata should retain their attribution/linking requirements. Existing YouTube Music playback remains available; this commit does not silently remove it or claim the audio extractor has been replaced.
