# Hosted Mirror

Receptor AirPlay para Fire TV: duplica la pantalla de un iPhone, iPad o Mac en el televisor.
Desarrollado por Hosted Comunicaciones y Publicidad (https://www.hosted.cl).

Hosted Mirror es software libre bajo la licencia **GNU GPL v3** (ver `LICENSE`).

## Cómo funciona

- `app/src/main/cpp/hm_bridge.cpp` — puente JNI entre el motor AirPlay de UxPlay y Android.
  Los callbacks nativos dejan video, audio y eventos en colas que los hilos Java consumen.
- `app/src/main/java/cl/hosted/mirror/engine/` — decodificación por hardware (MediaCodec) del
  video H.264/H.265 y del audio AAC/AAC-ELD; el ALAC se decodifica en nativo.
- `MirrorService` mantiene el receptor publicado (mDNS) y abre `MirrorActivity` al llegar video.

## Compilar

Requisitos: JDK 17 o 21, Android SDK (plataforma 34) y NDK 27.2.12479018.

```bash
# 1. Dependencias de terceros (una vez)
git clone https://github.com/FDH2/UxPlay.git third_party/UxPlay
git clone https://github.com/libimobiledevice/libplist.git third_party/libplist
git clone https://github.com/macosforge/alac.git third_party/alac
curl -L -o third_party/openssl.tar.gz https://github.com/openssl/openssl/releases/download/openssl-3.5.4/openssl-3.5.4.tar.gz
tar -xzf third_party/openssl.tar.gz -C third_party
scripts/build-openssl.sh

# 2. Parche para Android (nombre de host mDNS)
git -C third_party/UxPlay apply ../../patches/uxplay-android-hostname.patch

# 3. App
./gradlew assembleRelease
```

La versión firmada usa `keystore/keystore.properties`, que no forma parte del repositorio.
Sin ese archivo, la versión final se firma con la llave de depuración.

## Componentes de terceros

| Componente | Licencia | Uso |
|---|---|---|
| [UxPlay](https://github.com/FDH2/UxPlay) | GPL-3.0 (partes LGPL-2.1) | Protocolo AirPlay, pairing, FairPlay, mDNS |
| playfair (incluido en UxPlay) | GPL-3.0 | FairPlay |
| llhttp (incluido en UxPlay) | MIT | Parser HTTP |
| [libplist](https://github.com/libimobiledevice/libplist) | LGPL-2.1 | Listas de propiedades de Apple |
| [OpenSSL](https://www.openssl.org) 3.5.4 | Apache-2.0 | Criptografía |
| [Apple ALAC](https://github.com/macosforge/alac) | Apache-2.0 | Decodificador de audio ALAC |

AirPlay, iPhone, iPad y Mac son marcas registradas de Apple Inc. Hosted Mirror no está afiliado
ni respaldado por Apple.
