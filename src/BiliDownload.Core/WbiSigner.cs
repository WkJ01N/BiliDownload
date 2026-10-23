using System.Security.Cryptography;
using System.Text;

namespace BiliDownload.Core;

public static class WbiSigner
{
    private static readonly int[] Order = [46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52];
    public static string Sign(IEnumerable<KeyValuePair<string, string>> values, string imageKey, string subKey, long timestamp)
    {
        var key = imageKey + subKey;
        if (key.Length != 64) throw new BiliException("B 站签名信息无效，请稍后重试。");
        var mixin = new string(Order.Take(32).Select(i => key[i]).ToArray());
        var map = new SortedDictionary<string, string>(values.ToDictionary(), StringComparer.Ordinal) { ["wts"] = timestamp.ToString() };
        var query = string.Join("&", map.Select(p => Uri.EscapeDataString(p.Key) + "=" +
            Uri.EscapeDataString(new string(p.Value.Where(c => !"!'()*".Contains(c)).ToArray()))));
        return query + "&w_rid=" + Convert.ToHexString(MD5.HashData(Encoding.UTF8.GetBytes(query + mixin))).ToLowerInvariant();
    }
}
