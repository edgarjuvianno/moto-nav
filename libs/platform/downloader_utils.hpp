#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace downloader
{
std::string GetFileDownloadUrl(std::string const & fileName, int64_t dataVersion, uint64_t diffVersion = 0);
// Maps hosted outside the map servers. They keep the data version of the other maps, so a new build of them
// is told apart by its size.
bool IsExternalMap(std::string_view countryName);
// Absolute url of an external map, or an empty string.
std::string GetExternalMapUrl(std::string const & relativeUrl);
bool IsUrlSupported(std::string const & url);
std::string GetFilePathByUrl(std::string const & url);
}  // namespace downloader
